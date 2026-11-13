package com.eduaccess.bridgeaccess.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.repository.MyPackageRepository;
import com.eduaccess.bridgeaccess.storage.ImageStore;

/**
 * Removes the stored files of deleted packages (FR-5.8). {@code DELETE /me/packages/{id}}
 * calls {@link #clean} right away; the scheduled {@link #sweep} retries any package whose
 * files could not be removed then (storage down, instance stopped mid-way).
 *
 * <p>
 * The marker is the {@code package_images} rows: they are deleted only after the folder is
 * emptied, so a deleted package with image rows still needs cleaning. Both steps are
 * idempotent, so several instances may sweep at once.
 */
@Component
public class DeletedPackageCleaner {

	private static final Logger log = LoggerFactory.getLogger(DeletedPackageCleaner.class);

	/** Leave fresh deletions to the request that is cleaning them up. */
	static final Duration GRACE = Duration.ofMinutes(5);

	static final int BATCH = 50;

	private final MyPackageRepository repository;

	private final ImageStore imageStore;

	public DeletedPackageCleaner(MyPackageRepository repository, ImageStore imageStore) {
		this.repository = repository;
		this.imageStore = imageStore;
	}

	/**
	 * Empties the package's folder, then forgets its image rows.
	 *
	 * @return false if storage failed; the sweep tries again later
	 */
	public boolean clean(UUID packageId) {
		try {
			int files = this.imageStore.deleteFolder(packageId.toString());
			int rows = this.repository.deleteImageRows(packageId);
			log.info("Cleaned deleted package {}: {} file(s), {} image row(s)", packageId, files, rows);
			return true;
		}
		catch (RuntimeException ex) {
			log.warn("Could not clean files of deleted package {}; will retry", packageId, ex);
			return false;
		}
	}

	@Scheduled(initialDelayString = "${bridgeaccess.storage.cleanup-interval:15m}",
			fixedDelayString = "${bridgeaccess.storage.cleanup-interval:15m}")
	public void sweep() {
		List<UUID> pending;
		try {
			pending = this.repository.findDeletedWithImages(GRACE, BATCH);
		}
		catch (RuntimeException ex) {
			log.warn("Cleanup sweep could not read deleted packages; will try again next interval", ex);
			return;
		}
		int cleaned = 0;
		for (UUID id : pending) {
			if (clean(id)) {
				cleaned++;
			}
			else {
				break; // storage is likely down; stop hammering it until the next interval
			}
		}
		if (!pending.isEmpty()) {
			log.info("Cleanup sweep: {} of {} deleted package(s) cleaned", cleaned, pending.size());
		}
	}

}
