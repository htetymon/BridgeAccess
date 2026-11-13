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

	private final NamedParameterJdbcTemplate jdbc;

	public GenerationRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
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
