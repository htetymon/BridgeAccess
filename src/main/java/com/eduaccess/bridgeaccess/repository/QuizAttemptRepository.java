package com.eduaccess.bridgeaccess.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import com.eduaccess.bridgeaccess.dto.QuizAttemptView;

/**
 * {@code app.quiz_attempts} and their {@code app.quiz_answers}. The attempt ID comes from the
 * client, so a replayed request hits the primary key instead of creating a duplicate;
 * {@code qans_one_per_q} keeps one answer per question. Inserting a passing attempt grants
 * Learnt through the {@code trg_qa_grant_learnt} trigger (FR-7.6).
 */
@Repository
public class QuizAttemptRepository {

	/** What grading needs from a package: whether it accepts attempts, and its answer key. */
	public record AnswerKey(UUID questionId, int correctIndex) {
	}

	/** A graded attempt, ready to store. */
	public record NewAttempt(UUID id, UUID deviceId, UUID packageId, int score, int totalQuestions,
			int passMarkPercent, boolean passed, Instant attemptedAt, List<QuizAttemptView.Answer> answers) {
	}

	/** Filters and keyset position of {@code GET /me/quiz-attempts}. */
	public record PageQuery(UUID deviceId, UUID packageId, Instant afterTime, UUID afterId, int fetchSize) {
	}

	/** A stored attempt and the device it belongs to (for the replay check). */
	public record StoredAttempt(UUID deviceId, QuizAttemptView view) {
	}

	private static final String COLUMNS = """
			select qa.id, qa.device_id, qa.package_id, qa.score, qa.total_questions, qa.percentage,
			       qa.pass_mark_percent, qa.passed, qa.attempted_at, coalesce(pr.is_learnt, false) as is_learnt
			  from app.quiz_attempts qa
			  left join app.learning_progress pr on pr.device_id = qa.device_id and pr.package_id = qa.package_id
			""";

	private final NamedParameterJdbcTemplate jdbc;

	public QuizAttemptRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The answer key of a package that was published (a package deleted later still accepts
	 * attempts: devices keep their copy). Empty if the package is unknown or never published.
	 */
	public Optional<List<AnswerKey>> findAnswerKey(UUID packageId) {
		MapSqlParameterSource params = new MapSqlParameterSource("packageId", packageId);
		Boolean published = this.jdbc
			.query("select status = 'published' as ok from app.learning_packages where id = :packageId", params,
					(rs, i) -> rs.getBoolean("ok"))
			.stream()
			.findFirst()
			.orElse(false);
		if (!published) {
			return Optional.empty();
		}
		return Optional.of(this.jdbc.query(
				"select id, correct_index from app.quiz_questions where package_id = :packageId order by position",
				params, (rs, i) -> new AnswerKey(rs.getObject("id", UUID.class), rs.getInt("correct_index"))));
	}

	/**
	 * Stores the attempt and its answers. {@code attemptedAt} is capped at now.
	 *
	 * @return false if an attempt with this ID already exists (any device); nothing is written
	 */
	public boolean insert(NewAttempt attempt) {
		String sql = """
				insert into app.quiz_attempts
				       (id, device_id, package_id, score, total_questions, pass_mark_percent, passed, attempted_at)
				values (:id, :deviceId, :packageId, :score, :totalQuestions, :passMarkPercent, :passed,
				        least(cast(:attemptedAt as timestamptz), now()))
				on conflict (id) do nothing""";
		MapSqlParameterSource params = new MapSqlParameterSource("id", attempt.id())
			.addValue("deviceId", attempt.deviceId())
			.addValue("packageId", attempt.packageId())
			.addValue("score", attempt.score())
			.addValue("totalQuestions", attempt.totalQuestions())
			.addValue("passMarkPercent", attempt.passMarkPercent())
			.addValue("passed", attempt.passed())
			.addValue("attemptedAt", Sql.timestamp(attempt.attemptedAt()));
		if (this.jdbc.update(sql, params) == 0) {
			return false;
		}
		SqlParameterSource[] answers = attempt.answers()
			.stream()
			.map(a -> new MapSqlParameterSource("attemptId", attempt.id()).addValue("questionId", a.questionId())
				.addValue("selectedIndex", a.selectedIndex())
				.addValue("isCorrect", a.isCorrect()))
			.toArray(SqlParameterSource[]::new);
		this.jdbc.batchUpdate("""
				insert into app.quiz_answers (attempt_id, question_id, selected_index, is_correct)
				values (:attemptId, :questionId, :selectedIndex, :isCorrect)""", answers);
		return true;
	}

	/** Any device's attempt with this ID, so a replay can be told apart from an ID clash. */
	public Optional<StoredAttempt> find(UUID attemptId) {
		List<StoredAttempt> rows = this.jdbc.query(COLUMNS + " where qa.id = :id",
				new MapSqlParameterSource("id", attemptId), (rs, i) -> stored(rs, List.of()));
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		StoredAttempt row = rows.get(0);
		List<QuizAttemptView.Answer> answers = findAnswers(List.of(attemptId)).getOrDefault(attemptId, List.of());
		return Optional.of(new StoredAttempt(row.deviceId(), withAnswers(row.view(), answers)));
	}

	/**
	 * Newest first, keyset on {@code (attempted_at, id)}; served by {@code idx_qa_device_package}
	 * when filtered by package. Answers are loaded for the whole page in one more query.
	 */
	public List<QuizAttemptView> findPage(PageQuery query) {
		StringBuilder sql = new StringBuilder(COLUMNS).append(" where qa.device_id = :deviceId");
		MapSqlParameterSource params = new MapSqlParameterSource("deviceId", query.deviceId())
			.addValue("fetchSize", query.fetchSize());
		if (query.packageId() != null) {
			sql.append(" and qa.package_id = :packageId");
			params.addValue("packageId", query.packageId());
		}
		if (query.afterTime() != null) {
			sql.append(" and (qa.attempted_at, qa.id) < (:afterTime, :afterId)");
			params.addValue("afterTime", Sql.timestamp(query.afterTime())).addValue("afterId", query.afterId());
		}
		sql.append(" order by qa.attempted_at desc, qa.id desc limit :fetchSize");
		List<QuizAttemptView> rows = this.jdbc.query(sql.toString(), params, (rs, i) -> stored(rs, List.of()).view());
		if (rows.isEmpty()) {
			return rows;
		}
		Map<UUID, List<QuizAttemptView.Answer>> answers = findAnswers(rows.stream().map(QuizAttemptView::id).toList());
		return rows.stream().map(r -> withAnswers(r, answers.getOrDefault(r.id(), List.of()))).toList();
	}

	/** Answers in the quiz's question order. */
	private Map<UUID, List<QuizAttemptView.Answer>> findAnswers(List<UUID> attemptIds) {
		String sql = """
				select a.attempt_id, a.question_id, a.selected_index, a.is_correct
				  from app.quiz_answers a
				  join app.quiz_questions q on q.id = a.question_id
				 where a.attempt_id in (:ids)
				 order by a.attempt_id, q.position""";
		Map<UUID, List<QuizAttemptView.Answer>> byAttempt = new LinkedHashMap<>();
		this.jdbc.query(sql, new MapSqlParameterSource("ids", attemptIds), rs -> {
			byAttempt.computeIfAbsent(rs.getObject("attempt_id", UUID.class), k -> new ArrayList<>())
				.add(new QuizAttemptView.Answer(rs.getObject("question_id", UUID.class), rs.getInt("selected_index"),
						rs.getBoolean("is_correct")));
		});
		return byAttempt;
	}

	private static StoredAttempt stored(ResultSet rs, List<QuizAttemptView.Answer> answers) throws SQLException {
		return new StoredAttempt(rs.getObject("device_id", UUID.class),
				new QuizAttemptView(rs.getObject("id", UUID.class), rs.getObject("package_id", UUID.class),
						rs.getInt("score"), rs.getInt("total_questions"), rs.getDouble("percentage"),
						rs.getInt("pass_mark_percent"), rs.getBoolean("passed"), Sql.instant(rs, "attempted_at"),
						rs.getBoolean("is_learnt"), answers));
	}

	private static QuizAttemptView withAnswers(QuizAttemptView v, List<QuizAttemptView.Answer> answers) {
		return new QuizAttemptView(v.id(), v.packageId(), v.score(), v.totalQuestions(), v.percentage(),
				v.passMarkPercent(), v.passed(), v.attemptedAt(), v.isLearnt(), List.copyOf(answers));
	}

}
