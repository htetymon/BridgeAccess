package com.eduaccess.bridgeaccess.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.eduaccess.bridgeaccess.service.GeneratedLesson;

import tools.jackson.databind.JsonNode;

/**
 * Create to published (FR-5, FR-6): a contribution goes through the real job (source text,
 * lesson validation, image optimisation, saving, publishing) and the database's publish rules,
 * then shows up for learners.
 */
class CreateToPublishedIntegrationTest extends PostgresIntegrationTest {

	@Test
	void aContributionIsGeneratedSavedAndPublished() throws Exception {
		UUID id = contribute(DEVICE);

		JsonNode mine = awaitStatus(DEVICE, id, "published");
		assertThat(mine.get("title").asString()).isEqualTo("Renewable Energy Basics");
		assertThat(mine.get("subject").asString()).isEqualTo("Science");
		assertThat(mine.get("packageType").asString()).isEqualTo("with_visual"); // set by the image trigger
		assertThat(mine.get("publishedAt").isNull()).isFalse();

		// Learners see it in Explore (another device) ...
		JsonNode feed = json(as(OTHER_DEVICE, get("/api/v1/packages")), 200);
		assertThat(feed.get("items")).hasSize(1);
		assertThat(feed.get("items").get(0).get("id").asString()).isEqualTo(id.toString());
		assertThat(feed.get("items").get(0).get("sizeBytes").asInt()).isPositive();

		// ... can open its package screen ...
		JsonNode detail = json(as(OTHER_DEVICE, get("/api/v1/packages/" + id)), 200);
		assertThat(detail.get("original").get("author").asString()).isEqualTo("Laura Mitchell");
		assertThat(detail.get("keyTakeaways")).hasSize(3);
		assertThat(detail.get("coverImageUrl").asString()).endsWith("/images/" + firstImageId(id));

		// ... and get the full lesson, with the quiz in order and the optimised image.
		JsonNode bundle = json(as(OTHER_DEVICE, get("/api/v1/packages/" + id + "/bundle")), 200);
		assertThat(bundle.get("content").get("keyConcepts")).hasSize(4);
		assertThat(bundle.get("package").get("steps")).hasSize(4);
		JsonNode questions = bundle.get("quiz").get("questions");
		assertThat(questions).hasSize(5);
		for (int i = 0; i < 5; i++) {
			assertThat(questions.get(i).get("position").asInt()).isEqualTo(i + 1);
			assertThat(questions.get(i).get("correctIndex").asInt()).isEqualTo(i % 4);
			assertThat(questions.get(i).get("options")).hasSize(4);
		}
		JsonNode images = bundle.get("images");
		assertThat(images).hasSize(1);
		assertThat(images.get(0).get("mimeType").asString()).isIn("image/webp", "image/jpeg");
		assertThat(images.get(0).get("altText").asString()).isEqualTo("Wind turbines on a hill");
	}

	@Test
	void anInvalidLessonIsRetriedOnceThenTheContributorSeesAFailure() throws Exception {
		GeneratedLesson good = lesson();
		List<GeneratedLesson.QuizQuestion> fourQuestions = new ArrayList<>(good.quiz().subList(0, 4));
		GeneratedLesson broken = new GeneratedLesson(good.title(), good.subject(), good.overview(),
				good.keyTakeaways(), good.summary(), good.keyConcepts(), fourQuestions);
		given(this.gemini.generateLesson(any(), any())).willReturn(broken);

		UUID id = contribute(DEVICE);

		JsonNode mine = awaitFailure(id);
		assertThat(mine.get("failureReason").asString()).isNotBlank();
		// Nothing half-saved is visible to learners.
		assertThat(json(as(OTHER_DEVICE, get("/api/v1/packages")), 200).get("items")).isEmpty();
		assertThat(this.jdbc.queryForObject("select count(*) from app.quiz_questions where package_id = ?",
				Integer.class, id)).isZero();
	}

	private JsonNode awaitFailure(UUID id) throws Exception {
		for (int i = 0; i < 300; i++) {
			JsonNode row = json(as(DEVICE, get("/api/v1/me/packages/" + id)), 200);
			if ("failed".equals(row.get("status").asString())) {
				return row;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Package did not fail");
	}

	private String firstImageId(UUID packageId) {
		return this.jdbc.queryForObject(
				"select id::text from app.package_images where package_id = ? order by position limit 1", String.class,
				packageId);
	}

}
