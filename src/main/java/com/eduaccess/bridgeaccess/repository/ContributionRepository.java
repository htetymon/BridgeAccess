package com.eduaccess.bridgeaccess.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes and checks for contributor-created packages (API doc, section 8). */
@Repository
public class ContributionRepository {

	/** The live package that already uses a link, and the device that created it. */
	public record LinkOwner(UUID packageId, UUID deviceId) {
	}

	public record NewPackageRow(UUID id, UUID deviceId, String resourceType, String originalTitle, String author,
			String resourceLink, String sourceContent) {
	}

	public record NewImageRow(UUID packageId, int position, String storagePath, String mimeType, int sizeBytes,
			String altText) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	public ContributionRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * A live (processing or published, not deleted) package with the same normalised link.
	 * The key expression and the predicate match {@code resource_link_key} and the partial
	 * unique index {@code uq_lp_live_link}, so this is an index lookup.
	 */
	public Optional<LinkOwner> findLiveByLink(String resourceLink) {
		String sql = """
				select id, device_id
				  from app.learning_packages
				 where resource_link_key = lower(rtrim(btrim(:link), '/'))
				   and status in ('processing', 'published') and deleted_at is null""";
		List<LinkOwner> rows = this.jdbc.query(sql, new MapSqlParameterSource("link", resourceLink),
				(rs, i) -> new LinkOwner(rs.getObject("id", UUID.class), rs.getObject("device_id", UUID.class)));
		return rows.stream().findFirst();
	}

	/** Packages this device created since {@code since}, including failed and deleted ones. */
	public int countCreatedSince(UUID deviceId, Instant since) {
		String sql = """
				select count(*)
				  from app.learning_packages
				 where device_id = :deviceId and created_at >= :since""";
		Integer count = this.jdbc.queryForObject(sql, new MapSqlParameterSource("deviceId", deviceId)
			.addValue("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC)), Integer.class);
		return count == null ? 0 : count;
	}

	/**
	 * Serialises creations by one device until the current transaction ends, so two parallel
	 * requests cannot both pass the daily-limit count. Must run inside a transaction.
	 */
	public void lockDevice(UUID deviceId) {
		this.jdbc.queryForObject(
				"select 1 from (select pg_advisory_xact_lock(hashtextextended(cast(:deviceId as text), 5010))) l",
				new MapSqlParameterSource("deviceId", deviceId), Integer.class);
	}

	/** Inserts the package in {@code processing}; returns the database's {@code created_at}. */
	public Instant insertPackage(NewPackageRow row) {
		String sql = """
				insert into app.learning_packages
				       (id, device_id, original_title, author, resource_link, resource_type, source_content)
				values (:id, :deviceId, :originalTitle, :author, :resourceLink,
				        cast(:resourceType as app.resource_type), :sourceContent)
				returning created_at""";
		MapSqlParameterSource params = new MapSqlParameterSource("id", row.id()).addValue("deviceId", row.deviceId())
			.addValue("originalTitle", row.originalTitle())
			.addValue("author", row.author())
			.addValue("resourceLink", row.resourceLink())
			.addValue("resourceType", row.resourceType())
			.addValue("sourceContent", row.sourceContent());
		OffsetDateTime createdAt = this.jdbc.queryForObject(sql, params, OffsetDateTime.class);
		return createdAt.toInstant();
	}

	/** A contributor image. The {@code trg_pi_sync_type} trigger then sets {@code with_visual}. */
	public void insertImage(NewImageRow row) {
		String sql = """
				insert into app.package_images
				       (package_id, source, position, storage_path, mime_type, size_bytes, alt_text)
				values (:packageId, 'contributor', :position, :storagePath, :mimeType, :sizeBytes, :altText)""";
		this.jdbc.update(sql, new MapSqlParameterSource("packageId", row.packageId())
			.addValue("position", row.position())
			.addValue("storagePath", row.storagePath())
			.addValue("mimeType", row.mimeType())
			.addValue("sizeBytes", row.sizeBytes())
			.addValue("altText", row.altText()));
	}

}
