package com.eduaccess.bridgeaccess.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs every query in success_metrics.sql against the real schema with a small, known data
 * set, so each metric's number can be checked by hand:
 *
 * <pre>
 * device A: first seen today with a download of P1 (text only), every step done, passed 80 %
 * device B: only a bookmark (never downloaded)
 * device C: bookmarked 3 days ago, downloaded P2 (with visual) 1 day ago, no diagram step;
 *           failed 40 %, then passed 60 %; contributed P3, which failed
 * </pre>
 */
class MetricsQueriesIntegrationTest extends PostgresIntegrationTest {

	private static final UUID A = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");

	private static final UUID B = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");

	private static final UUID C = UUID.fromString("cccccccc-0000-4000-8000-000000000003");

	private static final UUID P1 = UUID.fromString("11111111-0000-4000-8000-000000000001");

	private static final UUID P2 = UUID.fromString("22222222-0000-4000-8000-000000000002");

	private static final UUID P3 = UUID.fromString("33333333-0000-4000-8000-000000000003");

	private static final UUID CONTRIBUTOR = UUID.fromString("dddddddd-0000-4000-8000-000000000004");

	private Map<String, String> queries;

	@BeforeEach
	void seed() throws Exception {
		this.queries = blocks(Files.readString(Path.of("success_metrics.sql"), StandardCharsets.UTF_8));

		// Two published packages by another contributor (seen 10 days ago), and C's failed one.
		insertPackage(P1, CONTRIBUTOR, "text_only", 30_000, "published", "10 days");
		insertPackage(P2, CONTRIBUTOR, "with_visual", 90_000, "published", "10 days");
		insertPackage(P3, C, "text_only", 0, "failed", "2 days");

		download(A, P1, 30_000, "1 hour");
		progress(A, P1, "{summary,key_concepts,quiz}");
		attempt(A, P1, 4, true, "30 minutes");

		this.jdbc.update("insert into app.bookmarks (device_id, package_id, created_at) values (?, ?, now())", B, P1);

		this.jdbc.update("insert into app.bookmarks (device_id, package_id, created_at) values (?, ?, now() - interval '3 days')",
				C, P2);
		download(C, P2, 90_000, "1 day");
		progress(C, P2, "{summary,key_concepts,quiz}"); // the diagram step is missing
		attempt(C, P2, 2, false, "20 hours");
		attempt(C, P2, 3, true, "19 hours");
	}

	@Test
	void everyMetricIsCovered() {
		assertThat(this.queries.keySet()).containsExactly("activation", "completion", "learning_outcome",
				"data_efficiency", "contribution");
	}

	@Test
	void activation() {
		// New in the last 30 days: A, B, C and the contributor. Only A downloaded within a day.
		Map<String, Object> row = run("activation");
		assertThat(row.get("new_devices")).isEqualTo(4L);
		assertThat(row.get("activated_devices")).isEqualTo(1L);
		assertThat(row.get("activation_rate")).isEqualTo(new BigDecimal("0.250"));
	}

	@Test
	void completion() {
		// A finished P1 (3 steps); C did not do P2's diagram step.
		Map<String, Object> row = run("completion");
		assertThat(row.get("downloaded_packages")).isEqualTo(2L);
		assertThat(row.get("completed_packages")).isEqualTo(1L);
		assertThat(row.get("completion_rate")).isEqualTo(new BigDecimal("0.500"));
	}

	@Test
	void learningOutcome() {
		// Attempts 80 % pass, 40 % fail, 60 % pass. First attempts: A passed, C failed.
		Map<String, Object> row = run("learning_outcome");
		assertThat(row.get("attempts")).isEqualTo(3L);
		assertThat(row.get("pass_rate")).isEqualTo(new BigDecimal("0.667"));
		assertThat(row.get("average_score_percent")).isEqualTo(new BigDecimal("60.0"));
		assertThat(row.get("first_attempts")).isEqualTo(2L);
		assertThat(row.get("first_attempt_pass_rate")).isEqualTo(new BigDecimal("0.500"));
	}

	@Test
	void dataEfficiency() {
		Map<String, Object> row = run("data_efficiency");
		assertThat(row.get("median_downloaded_bytes")).isEqualTo(60_000L);
		assertThat(row.get("p90_downloaded_bytes")).isEqualTo(84_000L);
		assertThat(row.get("median_published_package_bytes")).isEqualTo(60_000L);
	}

	@Test
	void contribution() {
		// Created: P1, P2 (contributor), P3 (C, failed). Finished: all three; 2 published.
		Map<String, Object> row = run("contribution");
		assertThat(row.get("packages_created")).isEqualTo(3L);
		assertThat(row.get("contributing_devices")).isEqualTo(2L);
		assertThat(row.get("packages_per_contributor")).isEqualTo(new BigDecimal("1.50"));
		assertThat(row.get("publish_rate")).isEqualTo(new BigDecimal("0.667"));
	}

	// ------------------------------------------------------------------ helpers

	private Map<String, Object> run(String metric) {
		return this.jdbc.queryForMap(this.queries.get(metric));
	}

	/** Splits the file into its "-- metric: name" blocks. */
	private static Map<String, String> blocks(String sql) {
		Map<String, String> blocks = new LinkedHashMap<>();
		String[] parts = sql.split("(?m)^-- metric: ");
		for (int i = 1; i < parts.length; i++) {
			String name = parts[i].substring(0, parts[i].indexOf('\n')).trim();
			String body = parts[i].substring(parts[i].indexOf('\n') + 1).trim();
			blocks.put(name, body.endsWith(";") ? body.substring(0, body.length() - 1) : body);
		}
		return blocks;
	}

	private void insertPackage(UUID id, UUID device, String type, int size, String status, String age) {
		boolean published = status.equals("published");
		this.jdbc.update("""
				insert into app.learning_packages (id, device_id, title, original_title, author, resource_link,
				       resource_type, subject, package_type, status, failure_reason, package_size_bytes,
				       published_at, created_at)
				values (?, ?, 'Title', 'Original', 'Author', ?, 'text', 'Science', cast(? as app.package_type),
				        cast(? as app.package_status), ?, ?, case when ? then now() - cast(? as interval) end,
				        now() - cast(? as interval))""",
				id, device, "https://example.org/" + id, type, status, published ? null : "It failed.", size, published,
				age, age);
	}

	private void download(UUID device, UUID pkg, int size, String age) {
		this.jdbc.update("""
				insert into app.downloads (device_id, package_id, status, size_bytes, created_at)
				values (?, ?, 'completed', ?, now() - cast(? as interval))""", device, pkg, size, age);
	}

	private void progress(UUID device, UUID pkg, String steps) {
		this.jdbc.update("""
				insert into app.learning_progress (device_id, package_id, completed_steps, created_at)
				values (?, ?, cast(? as text[]), now() - interval '1 hour')""", device, pkg, steps);
	}

	private void attempt(UUID device, UUID pkg, int score, boolean passed, String age) {
		this.jdbc.update("""
				insert into app.quiz_attempts (device_id, package_id, score, total_questions, pass_mark_percent, passed,
				       attempted_at, created_at)
				values (?, ?, ?, 5, 60, ?, now() - cast(? as interval), now() - cast(? as interval))""", device, pkg,
				score, passed, age, age);
	}

}
