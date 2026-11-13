package com.eduaccess.bridgeaccess.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.multipart.MultipartFile;

import com.eduaccess.bridgeaccess.config.ContributionConfig.ContributionProperties;
import com.eduaccess.bridgeaccess.error.ApiException;
import com.eduaccess.bridgeaccess.repository.ContributionRepository;
import com.eduaccess.bridgeaccess.repository.ContributionRepository.NewImageRow;
import com.eduaccess.bridgeaccess.repository.ContributionRepository.NewPackageRow;
import com.eduaccess.bridgeaccess.service.CreatePackageValidator.NewImage;
import com.eduaccess.bridgeaccess.service.CreatePackageValidator.NewPackage;
import com.eduaccess.bridgeaccess.service.generation.GenerationDispatcher;
import com.eduaccess.bridgeaccess.storage.ImageStore;

/**
 * Creates contributor packages ({@code POST /packages}). The package is saved in
 * {@code processing}; the AI job picks it up from there.
 *
 * Order of checks:
 * <ol>
 * <li>Input rules (400, 422, 413, 415), no database access.</li>
 * <li>Duplicate link (409). Before the daily limit, so a client retrying its own timed-out
 * request gets {@code isOwn: true} and follows the package instead of seeing a 429.</li>
 * <li>Daily limit (429), read without a lock to fail fast.</li>
 * <li>Images are stored, then one transaction locks the device, re-counts and inserts. The
 * lock closes the race between parallel requests; the unique index {@code uq_lp_live_link}
 * closes the race on the link. If the transaction fails, stored images are deleted.</li>
 * </ol>
 * Images are stored before the transaction so that no database connection is held during
 * uploads. After the commit the package is handed to the bounded generation pool and the
 * request returns without waiting for it.
 */
@Service
public class ContributionService {

	private static final Logger log = LoggerFactory.getLogger(ContributionService.class);

	public record CreatedPackage(UUID id, Instant createdAt) {
	}

	private final CreatePackageValidator validator;

	private final ContributionRepository repository;

	private final ImageStore imageStore;

	private final TransactionOperations transactions;

	private final ContributionProperties properties;

	private final Clock clock;

	private final GenerationDispatcher generationDispatcher;

	public ContributionService(CreatePackageValidator validator, ContributionRepository repository,
			ImageStore imageStore, TransactionOperations transactions, ContributionProperties properties, Clock clock,
			GenerationDispatcher generationDispatcher) {
		this.validator = validator;
		this.repository = repository;
		this.imageStore = imageStore;
		this.transactions = transactions;
		this.properties = properties;
		this.clock = clock;
		this.generationDispatcher = generationDispatcher;
	}

	public CreatedPackage create(UUID deviceId, String rawData, List<MultipartFile> files) {
		NewPackage pkg = this.validator.validate(rawData, files);
		rejectDuplicate(deviceId, pkg.resourceLink());
		Instant dayStart = startOfUtcDay();
		rejectOverLimit(this.repository.countCreatedSince(deviceId, dayStart));

		UUID id = UUID.randomUUID();
		Instant createdAt = insert(id, deviceId, pkg, dayStart);
		// Committed: hand it to the AI job. Never throws; a full queue leaves the package
		// waiting for the sweeper, and the client gets its 202 either way.
		this.generationDispatcher.submit(id);
		return new CreatedPackage(id, createdAt);
	}

	private Instant insert(UUID id, UUID deviceId, NewPackage pkg, Instant dayStart) {
		List<String> stored = new ArrayList<>();
		try {
			List<NewImageRow> imageRows = new ArrayList<>();
			for (NewImage image : pkg.images()) {
				String path = id + "/original-" + image.position() + "." + image.type().extension();
				this.imageStore.put(path, image.bytes(), image.type().mimeType());
				stored.add(path);
				imageRows.add(new NewImageRow(id, image.position(), path, image.type().mimeType(),
						image.bytes().length, image.altText()));
			}

			return this.transactions.execute(status -> {
				this.repository.lockDevice(deviceId);
				rejectOverLimit(this.repository.countCreatedSince(deviceId, dayStart));
				Instant created = this.repository.insertPackage(new NewPackageRow(id, deviceId, pkg.resourceType(),
						pkg.originalTitle(), pkg.author(), pkg.resourceLink(), pkg.content()));
				imageRows.forEach(this.repository::insertImage);
				return created;
			});
		}
		catch (DuplicateKeyException ex) {
			// Another request created a package with this link after our first check.
			deleteQuietly(stored);
			rejectDuplicate(deviceId, pkg.resourceLink());
			throw ex; // the conflicting package is gone again; report as an unexpected error
		}
		catch (RuntimeException ex) {
			deleteQuietly(stored);
			throw ex;
		}
	}

	private void rejectDuplicate(UUID deviceId, String resourceLink) {
		this.repository.findLiveByLink(resourceLink).ifPresent(owner -> {
			throw ApiException.duplicateLink(owner.packageId(), owner.deviceId().equals(deviceId));
		});
	}

	private void rejectOverLimit(int createdToday) {
		if (createdToday >= this.properties.dailyLimit()) {
			Instant resetsAt = startOfUtcDay().plus(Duration.ofDays(1));
			throw ApiException.dailyLimitReached(this.properties.dailyLimit(), resetsAt);
		}
	}

	/** The limit is per UTC calendar day: it resets for everyone at 00:00 UTC. */
	private Instant startOfUtcDay() {
		return LocalDate.now(this.clock.withZone(ZoneOffset.UTC)).atStartOfDay(ZoneOffset.UTC).toInstant();
	}

	private void deleteQuietly(List<String> paths) {
		for (String path : paths) {
			try {
				this.imageStore.delete(path);
			}
			catch (RuntimeException ex) {
				log.warn("Could not delete orphaned image {}", path, ex);
			}
		}
	}

}
