package com.eduaccess.bridgeaccess.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.eduaccess.bridgeaccess.dto.DownloadView;

/**
 * {@code app.downloads}: one row per device and package ({@code dl_one_per_device}), so
 * recording the same download again updates that row instead of adding one.
 */
@Repository
public class DownloadRepository {

	private static final String COLUMNS = "d.package_id, d.status, d.size_bytes, d.downloaded_at";

	/** On conflict: the stored row is an active completed download and the new record is not. */
	private static final String KEEP_COMPLETED = "(d.status = 'completed' and d.removed_at is null and excluded.status <> 'completed')";

	private final NamedParameterJdbcTemplate jdbc;

	public DownloadRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Records a download of a published, not deleted package. {@code completed} is sticky: while
	 * the row is completed and not removed, a later {@code started} or {@code failed} (an older
	 * event arriving late, or a re-download that failed) leaves it unchanged. Any record clears
	 * {@code removed_at}: the package is on the device again. {@code downloadedAt} defaults to
	 * now and is capped at now.
	 *
	 * @return the stored row, or empty if the package is not available for download
	 */
	public Optional<DownloadView> upsert(UUID deviceId, UUID packageId, String status, int sizeBytes,
			Instant downloadedAt) {
		String sql = """
				insert into app.downloads as d (device_id, package_id, status, size_bytes, downloaded_at)
				select cast(:deviceId as uuid), lp.id, cast(:status as text), :sizeBytes,
				       least(coalesce(cast(:downloadedAt as timestamptz), now()), now())
				  from app.learning_packages lp
				 where lp.id = :packageId and lp.status = 'published' and lp.deleted_at is null
				on conflict (device_id, package_id) do update
				   set status        = case when %1$s then d.status else excluded.status end,
				       size_bytes    = case when %1$s then d.size_bytes else excluded.size_bytes end,
				       downloaded_at = case when %1$s then d.downloaded_at else excluded.downloaded_at end,
				       removed_at    = null
				returning %2$s""".formatted(KEEP_COMPLETED, COLUMNS);
		MapSqlParameterSource params = new MapSqlParameterSource("deviceId", deviceId).addValue("packageId", packageId)
			.addValue("status", status)
			.addValue("sizeBytes", sizeBytes)
			.addValue("downloadedAt", Sql.timestamp(downloadedAt));
		return this.jdbc.query(sql, params, (rs, i) -> view(rs)).stream().findFirst();
	}

	/** Marks the package removed from the device. Progress, attempts and bookmark stay (FR-3.3). */
	public void markRemoved(UUID deviceId, UUID packageId) {
		this.jdbc.update("""
				update app.downloads set removed_at = now()
				 where device_id = :deviceId and package_id = :packageId and removed_at is null""",
				new MapSqlParameterSource("deviceId", deviceId).addValue("packageId", packageId));
	}

	/**
	 * Active downloads (completed, not removed), newest first, keyset on
	 * {@code (downloaded_at, package_id)}; served by the partial index {@code idx_dl_device_recent}.
	 */
	public List<DownloadView> findPage(UUID deviceId, Instant afterTime, UUID afterId, int fetchSize) {
		StringBuilder sql = new StringBuilder("select " + COLUMNS + " from app.downloads d"
				+ " where d.device_id = :deviceId and d.removed_at is null and d.status = 'completed'");
		MapSqlParameterSource params = new MapSqlParameterSource("deviceId", deviceId).addValue("fetchSize", fetchSize);
		if (afterTime != null) {
			sql.append(" and (d.downloaded_at, d.package_id) < (:afterTime, :afterId)");
			params.addValue("afterTime", Sql.timestamp(afterTime)).addValue("afterId", afterId);
		}
		sql.append(" order by d.downloaded_at desc, d.package_id desc limit :fetchSize");
		return this.jdbc.query(sql.toString(), params, (rs, i) -> view(rs));
	}

	private static DownloadView view(ResultSet rs) throws SQLException {
		return new DownloadView(rs.getObject("package_id", UUID.class), rs.getString("status"),
				rs.getLong("size_bytes"), Sql.instant(rs, "downloaded_at"));
	}

}
