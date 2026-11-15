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

import com.eduaccess.bridgeaccess.dto.ProgressView;

/**
 * {@code app.learning_progress}, one row per device and package (primary key). Merging is
 * done by the {@code trg_lprog_merge} trigger, so a replayed or out-of-order write can never
 * un-complete a step, drop Learnt, or rewind the resume point (FR-8.3).
 */
@Repository
public class LearningProgressRepository {

	private static final String COLUMNS = "p.package_id, p.completed_steps, p.last_step, p.last_opened_at, p.is_learnt, p.learnt_at";

	private final NamedParameterJdbcTemplate jdbc;

	public LearningProgressRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts or merges. Only for a package that was published (a package deleted later still
	 * accepts progress: devices keep their copy). {@code lastOpenedAt} is capped at now, so a
	 * device with a fast clock cannot pin the resume point in the future. A missing
	 * {@code lastStep} keeps the stored one.
	 *
	 * @param stepsLiteral Postgres array literal of already validated step names
	 * @return the merged row, or empty if the package was never published
	 */
	public Optional<ProgressView> upsert(UUID deviceId, UUID packageId, String stepsLiteral, String lastStep,
			Instant lastOpenedAt) {
		String sql = """
				insert into app.learning_progress as p (device_id, package_id, completed_steps, last_step, last_opened_at)
				select cast(:deviceId as uuid), lp.id, cast(:steps as text[]), cast(:lastStep as text),
				       least(coalesce(cast(:lastOpenedAt as timestamptz), now()), now())
				  from app.learning_packages lp
				 where lp.id = :packageId and lp.status = 'published'
				on conflict (device_id, package_id) do update
				   set completed_steps = excluded.completed_steps,
				       last_step = coalesce(excluded.last_step, p.last_step),
				       last_opened_at = excluded.last_opened_at
				returning """ + COLUMNS;
		MapSqlParameterSource params = new MapSqlParameterSource("deviceId", deviceId).addValue("packageId", packageId)
			.addValue("steps", stepsLiteral)
			.addValue("lastStep", lastStep)
			.addValue("lastOpenedAt", Sql.timestamp(lastOpenedAt));
		return this.jdbc.query(sql, params, (rs, i) -> view(rs)).stream().findFirst();
	}

	public Optional<ProgressView> find(UUID deviceId, UUID packageId) {
		String sql = "select " + COLUMNS
				+ " from app.learning_progress p where p.device_id = :deviceId and p.package_id = :packageId";
		return this.jdbc
			.query(sql, new MapSqlParameterSource("deviceId", deviceId).addValue("packageId", packageId),
					(rs, i) -> view(rs))
			.stream()
			.findFirst();
	}

	/** Newest opened first, keyset on {@code (last_opened_at, package_id)} ({@code idx_lprog_device_opened}). */
	public List<ProgressView> findPage(UUID deviceId, Instant afterTime, UUID afterId, int fetchSize) {
		StringBuilder sql = new StringBuilder("select " + COLUMNS + " from app.learning_progress p where p.device_id = :deviceId");
		MapSqlParameterSource params = new MapSqlParameterSource("deviceId", deviceId).addValue("fetchSize", fetchSize);
		if (afterTime != null) {
			sql.append(" and (p.last_opened_at, p.package_id) < (:afterTime, :afterId)");
			params.addValue("afterTime", Sql.timestamp(afterTime)).addValue("afterId", afterId);
		}
		sql.append(" order by p.last_opened_at desc, p.package_id desc limit :fetchSize");
		return this.jdbc.query(sql.toString(), params, (rs, i) -> view(rs));
	}

	private static ProgressView view(ResultSet rs) throws SQLException {
		return new ProgressView(rs.getObject("package_id", UUID.class), Sql.textArray(rs, "completed_steps"),
				rs.getString("last_step"), Sql.instant(rs, "last_opened_at"), rs.getBoolean("is_learnt"),
				Sql.instant(rs, "learnt_at"));
	}

}
