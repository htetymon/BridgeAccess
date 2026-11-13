package com.eduaccess.bridgeaccess.repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Job bookkeeping on {@code app.learning_packages}. A package waiting for the job is
 * {@code processing} with {@code processing_started_at} null; claiming it sets
 * {@code processing_started_at}, which then serves as the claim token. Every write is a single
 * conditional statement, so concurrent workers, sweepers and instances cannot both win.
 */
@Repository
public class GenerationRepository {

	/** What the job builds a lesson from. {@code sourceContent} is null when nothing was pasted. */
	public record PackageSource(UUID id, String resourceType, String originalTitle, String author,
			String resourceLink, String sourceContent) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	public GenerationRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<PackageSource> findSource(UUID packageId) {
		String sql = """
				select id, resource_type::text as resource_type, original_title, author, resource_link, source_content
				  from app.learning_packages
				 where id = :id""";
		List<PackageSource> rows = this.jdbc.query(sql, new MapSqlParameterSource("id", packageId),
				(rs, i) -> new PackageSource(rs.getObject("id", UUID.class), rs.getString("resource_type"),
						rs.getString("original_title"), rs.getString("author"), rs.getString("resource_link"),
						rs.getString("source_content")));
		return rows.stream().findFirst();
	}

	/**
	 * Claims a waiting package for this worker. Empty when it was already claimed, published,
	 * failed or deleted, in which case the worker must do nothing.
	 */
	public Optional<Instant> claim(UUID packageId) {
		String sql = """
				update app.learning_packages
				   set processing_started_at = now()
				 where id = :id and status = 'processing' and processing_started_at is null and deleted_at is null
				returning processing_started_at""";
		List<OffsetDateTime> rows = this.jdbc.queryForList(sql, new MapSqlParameterSource("id", packageId),
				OffsetDateTime.class);
		return rows.stream().findFirst().map(OffsetDateTime::toInstant);
	}

	/** One {@code package_images} row as stored. */
	public record StoredImage(UUID id, String source, int position, String storagePath, String mimeType,
			int sizeBytes) {
	}

	public List<StoredImage> findImages(UUID packageId) {
		String sql = """
				select id, source::text as source, position, storage_path, mime_type, size_bytes
				  from app.package_images
				 where package_id = :packageId
				 order by position""";
		return this.jdbc.query(sql, new MapSqlParameterSource("packageId", packageId),
				(rs, i) -> new StoredImage(rs.getObject("id", UUID.class), rs.getString("source"), rs.getInt("position"),
						rs.getString("storage_path"), rs.getString("mime_type"), rs.getInt("size_bytes")));
	}

	/**
	 * Points an image row at its optimised file, only while the claim is current, so a job
	 * that outlived its timeout cannot change a package it no longer owns. Also refuses once the
	 * contributor deleted the package, so no new file is left behind after its folder was emptied.
	 */
	public boolean replaceImage(UUID packageId, Instant claimedAt, UUID imageId, String storagePath, String mimeType,
			int sizeBytes) {
		String sql = """
				update app.package_images
				   set storage_path = :storagePath, mime_type = :mimeType, size_bytes = :sizeBytes
				 where id = :imageId and package_id = :packageId
				   and exists (select 1 from app.learning_packages
				                where id = :packageId and status = 'processing' and processing_started_at = :claimedAt
				                  and deleted_at is null)""";
		return this.jdbc.update(sql, claimParams(packageId, claimedAt).addValue("imageId", imageId)
			.addValue("storagePath", storagePath)
			.addValue("mimeType", mimeType)
			.addValue("sizeBytes", sizeBytes)) == 1;
	}

	/** Removes an image row that must not be published, while the claim is current. */
	public boolean deleteImage(UUID packageId, Instant claimedAt, UUID imageId) {
		String sql = """
				delete from app.package_images
				 where id = :imageId and package_id = :packageId
				   and exists (select 1 from app.learning_packages
				                where id = :packageId and status = 'processing' and processing_started_at = :claimedAt
				                  and deleted_at is null)""";
		return this.jdbc.update(sql, claimParams(packageId, claimedAt).addValue("imageId", imageId)) == 1;
	}

	private static MapSqlParameterSource claimParams(UUID packageId, Instant claimedAt) {
		return new MapSqlParameterSource("packageId", packageId).addValue("claimedAt",
				OffsetDateTime.ofInstant(claimedAt, ZoneOffset.UTC));
	}

	/**
	 * Puts a claimed package back to waiting (unclaimed), for a later attempt when Gemini was
	 * busy. Only while the claim is current, the package is not deleted, and it was created
	 * less than {@code window} ago.
	 */
	public boolean release(UUID packageId, Instant claimedAt, Duration window) {
		String sql = """
				update app.learning_packages
				   set processing_started_at = null
				 where id = :packageId and status = 'processing' and processing_started_at = :claimedAt
				   and deleted_at is null
				   and created_at > now() - make_interval(secs => :windowSeconds)""";
		return this.jdbc.update(sql,
				claimParams(packageId, claimedAt).addValue("windowSeconds", window.toMillis() / 1000.0)) == 1;
	}

	/** Counts one AI attempt ({@code attempt_count}) while the claim is still current. */
	public boolean recordAttempt(UUID packageId, Instant claimedAt) {
		String sql = """
				update app.learning_packages
				   set attempt_count = attempt_count + 1
				 where id = :id and status = 'processing' and processing_started_at = :claimedAt and deleted_at is null""";
		return this.jdbc.update(sql, new MapSqlParameterSource("id", packageId).addValue("claimedAt",
				OffsetDateTime.ofInstant(claimedAt, ZoneOffset.UTC))) == 1;
	}

	/**
	 * Marks a claimed package failed. Does nothing if the claim is no longer current (the job
	 * timed out and the package was retried, published or deleted meanwhile).
	 */
	public boolean markFailed(UUID packageId, Instant claimedAt, String reason) {
		String sql = """
				update app.learning_packages
				   set status = 'failed', failure_reason = :reason
				 where id = :id and status = 'processing' and processing_started_at = :claimedAt""";
		return this.jdbc.update(sql, new MapSqlParameterSource("id", packageId).addValue("reason", reason)
			.addValue("claimedAt", OffsetDateTime.ofInstant(claimedAt, ZoneOffset.UTC))) == 1;
	}

	/** Waiting packages, oldest first, that have not changed for at least {@code grace}. */
	public List<UUID> findWaiting(Duration grace, int limit) {
		String sql = """
				select id
				  from app.learning_packages
				 where status = 'processing' and processing_started_at is null and deleted_at is null
				   and updated_at < now() - make_interval(secs => :graceSeconds)
				 order by updated_at
				 limit :limit""";
		return this.jdbc.queryForList(sql,
				new MapSqlParameterSource("graceSeconds", grace.toMillis() / 1000.0).addValue("limit", limit),
				UUID.class);
	}

	/**
	 * Fails packages that have waited to start (never claimed) longer than {@code maxWait}
	 * since their last change; returns their IDs. Normally a package is claimed within seconds,
	 * and a deferral resets the clock, so only a package the queue keeps missing gets here.
	 */
	public List<UUID> failNeverStarted(Duration maxWait, String reason) {
		String sql = """
				update app.learning_packages
				   set status = 'failed', failure_reason = :reason
				 where status = 'processing' and processing_started_at is null and deleted_at is null
				   and updated_at < now() - make_interval(secs => :maxWaitSeconds)
				returning id""";
		return this.jdbc.queryForList(sql, new MapSqlParameterSource("reason", reason).addValue("maxWaitSeconds",
				maxWait.toMillis() / 1000.0), UUID.class);
	}

	/** Fails every package claimed longer ago than {@code timeout}; returns their IDs. */
	public List<UUID> failStuck(Duration timeout, String reason) {
		String sql = """
				update app.learning_packages
				   set status = 'failed', failure_reason = :reason
				 where status = 'processing'
				   and processing_started_at < now() - make_interval(secs => :timeoutSeconds)
				returning id""";
		return this.jdbc.queryForList(sql, new MapSqlParameterSource("reason", reason).addValue("timeoutSeconds",
				timeout.toMillis() / 1000.0), UUID.class);
	}

}
