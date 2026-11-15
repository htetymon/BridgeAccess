package com.eduaccess.bridgeaccess.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

/**
 * Replaying one sync batch (FR-8.2, FR-8.3): the same outbox sent twice, as after a lost
 * response, against the real database. The second send must report every action ok and
 * change nothing: one attempt with its five answers, one bookmark, one download, and progress
 * that never loses a completed step. Uniqueness comes from the database's keys and triggers.
 */
class SyncReplayIntegrationTest extends PostgresIntegrationTest {

	private static final UUID ATTEMPT = UUID.fromString("c3f5b2a1-6d4e-4f08-8a7b-9e1d2c3b4a5f");

	@Test
	void sendingTheSameBatchTwiceCreatesNoDuplicates() throws Exception {
		UUID pkg = publishedPackage();
		String batch = batch(pkg, answers(pkg));

		JsonNode first = sync(batch);
		JsonNode second = sync(batch);

		for (JsonNode response : List.of(first, second)) {
			assertThat(response.get("results")).hasSize(6);
			for (JsonNode result : response.get("results")) {
				assertThat(result.get("status").asString()).as(result.toString()).isEqualTo("ok");
			}
		}
		assertThat(count("app.quiz_attempts")).isEqualTo(1);
		assertThat(count("app.quiz_answers")).isEqualTo(5);
		assertThat(count("app.bookmarks")).isEqualTo(1);
		assertThat(count("app.downloads")).isEqualTo(1);
		assertThat(count("app.learning_progress")).isEqualTo(1);

		// Both progress actions merged: steps unioned, the later resume point kept, and the
		// passing attempt (graded by the server: 4 of 5) granted Learnt.
		Map<String, Object> progress = this.jdbc.queryForMap(
				"select array_to_string(completed_steps, ',') as steps, last_step, is_learnt from app.learning_progress");
		assertThat(progress.get("steps")).isEqualTo("key_concepts,quiz,summary");
		assertThat(progress.get("last_step")).isEqualTo("quiz");
		assertThat(progress.get("is_learnt")).isEqualTo(true);

		JsonNode attempts = json(as(DEVICE, get("/api/v1/me/quiz-attempts?packageId=" + pkg)), 200).get("items");
		assertThat(attempts).hasSize(1);
		assertThat(attempts.get(0).get("score").asInt()).isEqualTo(4);
		assertThat(attempts.get(0).get("passed").asBoolean()).isTrue();

		// The restore endpoints read the same rows back.
		JsonNode bookmarks = json(as(DEVICE, get("/api/v1/me/bookmarks")), 200).get("items");
		assertThat(bookmarks).hasSize(1);
		assertThat(bookmarks.get(0).get("package").get("id").asString()).isEqualTo(pkg.toString());
		JsonNode progressRows = json(as(DEVICE, get("/api/v1/me/progress")), 200).get("items");
		assertThat(progressRows).hasSize(1);
		assertThat(progressRows.get(0).get("isLearnt").asBoolean()).isTrue();
	}

	@Test
	void anOlderProgressActionArrivingLateNeverUncompletesAStepOrRewinds() throws Exception {
		UUID pkg = publishedPackage();
		sync(ops(op("progress.upsert", Map.of("packageId", pkg, "completedSteps", List.of("summary", "key_concepts"),
				"lastStep", "diagram", "lastOpenedAt", "2026-11-10T10:00:00Z"))));

		// Sent later, but describes an earlier moment with fewer steps.
		JsonNode late = sync(ops(op("progress.upsert", Map.of("packageId", pkg, "completedSteps", List.of("summary"),
				"lastStep", "summary", "lastOpenedAt", "2026-11-10T09:00:00Z"))));

		assertThat(late.get("results").get(0).get("status").asString()).isEqualTo("ok");
		Map<String, Object> progress = this.jdbc
			.queryForMap("select array_to_string(completed_steps, ',') as steps, last_step from app.learning_progress");
		assertThat(progress.get("steps")).isEqualTo("key_concepts,summary");
		assertThat(progress.get("last_step")).isEqualTo("diagram");
	}

	@Test
	void aRejectedActionDoesNotStopTheRestAndIsRejectedAgainOnReplay() throws Exception {
		UUID pkg = publishedPackage();
		String batch = ops(op("bookmark.add", Map.of("packageId", UUID.randomUUID())), // no such package
				op("bookmark.add", Map.of("packageId", pkg)));

		for (int i = 0; i < 2; i++) {
			JsonNode results = sync(batch).get("results");
			assertThat(results.get(0).get("status").asString()).isEqualTo("rejected");
			assertThat(results.get(0).get("error").get("code").asString()).isEqualTo("NOT_FOUND");
			assertThat(results.get(1).get("status").asString()).isEqualTo("ok");
		}
		assertThat(count("app.bookmarks")).isEqualTo(1);
	}

	// ------------------------------------------------------------------ helpers

	private JsonNode sync(String body) throws Exception {
		return json(as(DEVICE, post("/api/v1/sync")).contentType(MediaType.APPLICATION_JSON).content(body), 200);
	}

	private int count(String table) {
		return this.jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

	/** The outbox a learner builds offline: read, take the quiz, bookmark, download. */
	private String batch(UUID pkg, List<Map<String, Object>> answers) {
		return ops(
				op("progress.upsert", Map.of("packageId", pkg, "completedSteps", List.of("summary"), "lastStep",
						"key_concepts", "lastOpenedAt", "2026-11-10T09:00:00Z")),
				op("quiz.attempt.create", Map.of("id", ATTEMPT, "packageId", pkg, "attemptedAt", "2026-11-10T09:20:00Z",
						"passMarkPercent", 60, "answers", answers)),
				op("bookmark.add", Map.of("packageId", pkg)),
				op("download.record", Map.of("packageId", pkg, "status", "completed", "sizeBytes", 64740)),
				op("progress.upsert", Map.of("packageId", pkg, "completedSteps", List.of("key_concepts", "quiz"),
						"lastStep", "quiz", "lastOpenedAt", "2026-11-10T09:21:00Z")),
				op("download.record", Map.of("packageId", pkg, "status", "completed", "sizeBytes", 64740)));
	}

	/** Answers to the package's real questions (from its bundle): the first four right, the last wrong. */
	private List<Map<String, Object>> answers(UUID pkg) throws Exception {
		JsonNode questions = json(as(DEVICE, get("/api/v1/packages/" + pkg + "/bundle")), 200).get("quiz").get("questions");
		List<Map<String, Object>> answers = new ArrayList<>();
		for (int i = 0; i < questions.size(); i++) {
			int correct = questions.get(i).get("correctIndex").asInt();
			answers.add(Map.of("questionId", questions.get(i).get("id").asString(), "selectedIndex",
					i < 4 ? correct : (correct + 1) % 4));
		}
		return answers;
	}

	private Map<String, Object> op(String type, Map<String, Object> payload) {
		Map<String, Object> op = new LinkedHashMap<>();
		op.put("opId", UUID.randomUUID());
		op.put("type", type);
		op.put("clientTime", "2026-11-10T09:30:00Z");
		op.put("payload", payload);
		return op;
	}

	@SafeVarargs
	private String ops(Map<String, Object>... ops) {
		return this.objectMapper.writeValueAsString(Map.of("ops", List.of(ops)));
	}

}
