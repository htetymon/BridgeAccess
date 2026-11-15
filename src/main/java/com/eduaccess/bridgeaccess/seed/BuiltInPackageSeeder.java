package com.eduaccess.bridgeaccess.seed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.eduaccess.bridgeaccess.storage.ImageStore;

/**
 * Makes a fresh installation useful immediately by publishing a small built-in catalogue.
 * The data lives in {@code seed/built-in-packages.sql}; this class only adds the one local
 * image, runs the script in a transaction, and never deletes existing packages. Re-running is
 * a no-op, so restarts (and integration tests) cannot duplicate rows or remove downloads,
 * bookmarks or progress.
 */
@Component
public class BuiltInPackageSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(BuiltInPackageSeeder.class);

	private static final UUID IMAGE_PACKAGE_ID = UUID.fromString("b0000000-0000-4000-8000-000000000001");

	private static final UUID IMAGE_ID = UUID.fromString("b1000000-0000-4000-8000-000000000001");

	private static final String IMAGE_PATH = IMAGE_PACKAGE_ID + "/" + IMAGE_ID + ".png";

	private static final String IMAGE_ALT = "Four renewable energy sources: solar, wind, hydro and geothermal.";

	private static final List<UUID> PACKAGE_IDS = List.of(IMAGE_PACKAGE_ID,
			UUID.fromString("b0000000-0000-4000-8000-000000000002"),
			UUID.fromString("b0000000-0000-4000-8000-000000000003"),
			UUID.fromString("b0000000-0000-4000-8000-000000000004"),
			UUID.fromString("b0000000-0000-4000-8000-000000000005"),
			UUID.fromString("b0000000-0000-4000-8000-000000000006"));

	private final JdbcTemplate jdbc;

	private final ImageStore imageStore;

	private final TransactionTemplate transactions;

	private final boolean enabled;

	private final Resource script = new ClassPathResource("seed/built-in-packages.sql");

	private final Resource image = new ClassPathResource("seed/renewable-energy.png");

	public BuiltInPackageSeeder(JdbcTemplate jdbc, ImageStore imageStore, PlatformTransactionManager transactionManager,
			@Value("${bridgeaccess.seed.built-in:true}") boolean enabled) {
		this.jdbc = jdbc;
		this.imageStore = imageStore;
		this.transactions = new TransactionTemplate(transactionManager);
		this.enabled = enabled;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!this.enabled) {
			log.info("Built-in package seeding is disabled");
			return;
		}
		try {
			int inserted = seed();
			if (inserted > 0) {
				log.info("Seeded {} built-in learning packages", inserted);
			}
		}
		catch (RuntimeException ex) {
			// A database outage must not stop the app; the health endpoint and retries still work.
			log.error("Could not seed built-in learning packages; Explore may be empty until this is fixed", ex);
		}
	}

	/**
	 * Inserts any missing built-in packages and returns how many were added. Public so an
	 * integration test can exercise the same path after emptying the tables.
	 */
	public int seed() {
		int before = countBuiltIns();
		boolean imageExisted = packageExists(IMAGE_PACKAGE_ID);
		byte[] imageBytes = ensureImageFile();
		try {
			this.transactions.executeWithoutResult(status -> {
				try {
					this.jdbc.execute(this.script.getContentAsString(StandardCharsets.UTF_8));
				}
				catch (IOException ex) {
					throw new UncheckedIOException("Could not read built-in package seed script", ex);
				}
				ensureImageRow(imageBytes);
			});
		}
		catch (RuntimeException ex) {
			deleteUnownedImage(imageExisted);
			throw ex;
		}
		// A duplicate contributed link can make the image package's insert a no-op. Do not
		// leave an unreferenced file behind in that case.
		deleteUnownedImage(imageExisted);
		return countBuiltIns() - before;
	}

	private byte[] ensureImageFile() {
		try {
			return this.imageStore.get(IMAGE_PATH);
		}
		catch (ImageStore.ImageNotFoundException ex) {
			try {
				byte[] bytes = this.image.getContentAsByteArray();
				this.imageStore.put(IMAGE_PATH, bytes, "image/png");
				return bytes;
			}
			catch (IOException io) {
				throw new UncheckedIOException("Could not read built-in package image", io);
			}
		}
	}

	private void ensureImageRow(byte[] bytes) {
		this.jdbc.update("""
				insert into app.package_images
				  (id, package_id, source, position, storage_path, mime_type, size_bytes, alt_text)
				select ?, p.id, 'contributor', 1, ?, 'image/png', ?, ?
				  from app.learning_packages p
				 where p.id = ?
				on conflict do nothing
				""", IMAGE_ID, IMAGE_PATH, bytes.length, IMAGE_ALT, IMAGE_PACKAGE_ID);
	}

	private void deleteUnownedImage(boolean imageExisted) {
		if (!imageExisted && !packageExists(IMAGE_PACKAGE_ID)) {
			this.imageStore.delete(IMAGE_PATH);
		}
	}

	private int countBuiltIns() {
		Integer count = this.jdbc.queryForObject(
				"select count(*) from app.learning_packages where id in (?,?,?,?,?,?)", Integer.class,
				PACKAGE_IDS.toArray());
		return count == null ? 0 : count;
	}

	private boolean packageExists(UUID id) {
		Integer count = this.jdbc.queryForObject("select count(*) from app.learning_packages where id = ?",
				Integer.class, id);
		return count != null && count > 0;
	}

}
