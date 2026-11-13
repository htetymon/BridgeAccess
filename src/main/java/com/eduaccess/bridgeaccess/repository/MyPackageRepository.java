package com.eduaccess.bridgeaccess.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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

import com.eduaccess.bridgeaccess.dto.MyPackage;

/**
 * A contributor's own packages ({@code /me/packages}). Every statement filters on
 * {@code device_id}, so another device's package behaves exactly like a missing one: the
 * backend uses the service role, which bypasses RLS, so this is the ownership check.
 */
@Repository
public class MyPackageRepository {

	/** {@link MyPackage} plus what retry needs: the link and the Gemini calls used so far. */
	public record OwnedPackage(MyPackage view, String resourceLink, int attemptCount) {
	}

	/** Filters and keyset position of {@code GET /me/packages}. */
	public record PageQuery(UUID deviceId, String status, Instant afterCreatedAt, UUID afterId, int fetchSize) {
	}

	private static final String COLUMNS = """
			select lp.id, lp.title, lp.original_title, lp.subject, lp.resource_type::text as resource_type,
			       lp.package_type::text as package_type, lp.status::text as status, lp.failure_reason,
			       lp.package_size_bytes, lp.created_at, lp.published_at, lp.resource_link, lp.attempt_count,
			       coalesce(pr.is_learnt, false) as is_learnt
			  from app.learning_packages lp
			  left join app.learning_progress pr on pr.package_id = lp.id and pr.device_id = :deviceId
			""";

	private final NamedParameterJdbcTemplate jdbc;

	public MyPackageRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** This device's package unless deleted; empty if missing, deleted or someone else's. */
	public Optional<OwnedPackage> findOwned(UUID deviceId, UUID packageId) {
		String sql = COLUMNS + " where lp.id = :id and lp.device_id = :deviceId and lp.deleted_at is null";
		return this.jdbc.query(sql, params(deviceId, packageId), (rs, i) -> owned(rs)).stream().findFirst();
	}

	/**
	 * One page of My Contributions, newest first, every status except deleted. Ordered by
	 * {@code (created_at desc, id desc)}, which {@code idx_lp_device_created} serves.
	 */
	public List<MyPackage> findPage(PageQuery query) {
		StringBuilder sql = new StringBuilder(COLUMNS)
			.append(" where lp.device_id = :deviceId and lp.deleted_at is null");
		MapSqlParameterSource params = new MapSqlParameterSource("deviceId", query.deviceId())
			.addValue("fetchSize", query.fetchSize());
		if (query.status() != null) {
			sql.append(" and lp.status = cast(:status as app.package_status)");
			params.addValue("status", query.status());
		}
		if (query.afterCreatedAt() != null) {
			sql.append(" and (lp.created_at, lp.id) < (:afterCreatedAt, :afterId)");
			params.addValue("afterCreatedAt", OffsetDateTime.ofInstant(query.afterCreatedAt(), ZoneOffset.UTC))
				.addValue("afterId", query.afterId());
		}
		sql.append(" order by lp.created_at desc, lp.id desc limit :fetchSize");
		return this.jdbc.query(sql.toString(), params, (rs, i) -> view(rs));
	}

	/**
	 * Soft-deletes (FR-5.8): the row stays for devices that already downloaded it, but it
	 * leaves Explore, lists and the AI job.
	 *
	 * @return false if missing, already deleted or someone else's
	 */
	public boolean softDelete(UUID deviceId, UUID packageId) {
		String sql = """
				update app.learning_packages
				   set deleted_at = now()
				 where id = :id and device_id = :deviceId and deleted_at is null""";
		return this.jdbc.update(sql, params(deviceId, packageId)) == 1;
	}

	/**
	 * Puts a failed package back in the queue: {@code processing}, unclaimed, no reason.
	 * Content and images stay and are reused. May throw {@code DuplicateKeyException} when
	 * another live package took the same link since this one failed.
	 *
	 * @param maxAttempts refuses once the package has used this many Gemini calls
	 * @return false unless it was this device's, not deleted, {@code failed} and within budget
	 */
	public boolean resetForRetry(UUID deviceId, UUID packageId, int maxAttempts) {
		String sql = """
				update app.learning_packages
				   set status = 'processing', failure_reason = null, processing_started_at = null
				 where id = :id and device_id = :deviceId and deleted_at is null and status = 'failed'
				   and attempt_count < :maxAttempts""";
		return this.jdbc.update(sql, params(deviceId, packageId).addValue("maxAttempts", maxAttempts)) == 1;
	}

	/**
	 * Deleted packages that still have image rows, oldest deletion first. The rows are removed
	 * only after the files are, so a row on a deleted package means "files may remain".
	 *
	 * @param grace skip very recent deletions, which the delete request is still cleaning up
	 */
	public List<UUID> findDeletedWithImages(Duration grace, int limit) {
		String sql = """
				select lp.id
				  from app.learning_packages lp
				 where lp.deleted_at is not null
				   and lp.deleted_at < now() - make_interval(secs => :graceSeconds)
				   and exists (select 1 from app.package_images pi where pi.package_id = lp.id)
				 order by lp.deleted_at
				 limit :limit""";
		return this.jdbc.queryForList(sql,
				new MapSqlParameterSource("graceSeconds", grace.toMillis() / 1000.0).addValue("limit", limit), UUID.class);
	}

	/** Forgets the images of a deleted package once its files are gone. */
	public int deleteImageRows(UUID packageId) {
		String sql = """
				delete from app.package_images pi
				 where pi.package_id = :id
				   and exists (select 1 from app.learning_packages lp where lp.id = :id and lp.deleted_at is not null)""";
		return this.jdbc.update(sql, new MapSqlParameterSource("id", packageId));
	}

	private static MapSqlParameterSource params(UUID deviceId, UUID packageId) {
		return new MapSqlParameterSource("id", packageId).addValue("deviceId", deviceId);
	}

	private static OwnedPackage owned(ResultSet rs) throws SQLException {
		return new OwnedPackage(view(rs), rs.getString("resource_link"), rs.getInt("attempt_count"));
	}

	private static MyPackage view(ResultSet rs) throws SQLException {
		return new MyPackage(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("original_title"),
				rs.getString("subject"), rs.getString("resource_type"), rs.getString("package_type"),
				rs.getString("status"), rs.getString("failure_reason"), rs.getInt("package_size_bytes"),
				instant(rs, "created_at"), instant(rs, "published_at"), rs.getBoolean("is_learnt"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
