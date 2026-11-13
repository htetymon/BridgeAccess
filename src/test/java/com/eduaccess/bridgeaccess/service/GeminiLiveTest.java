package com.eduaccess.bridgeaccess.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.eduaccess.bridgeaccess.config.GeminiConfig;
import com.eduaccess.bridgeaccess.config.GeminiConfig.GeminiProperties;
import com.eduaccess.bridgeaccess.service.GeminiService.LessonSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the real Gemini API once (costs a few thousand tokens). Runs only when
 * {@code GEMINI_API_KEY} is set, so CI and machines without a key skip it. Checks the
 * connection and that the answer fits the schema; content quality is the validator's job.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiLiveTest {

	private static final String SOURCE = """
			Renewable energy comes from sources that are naturally replenished on a human timescale, \
			such as sunlight, wind, rain, tides, waves and geothermal heat. Solar panels turn sunlight \
			into electricity using photovoltaic cells. Wind turbines use the kinetic energy of moving air \
			to spin blades connected to a generator. Hydropower uses flowing water, usually held behind a \
			dam, to turn turbines. Geothermal plants use heat from inside the Earth to make steam that \
			drives turbines. Unlike coal, oil and natural gas, renewable sources do not run out and \
			produce little or no greenhouse gas while generating power. Their main challenge is that sun \
			and wind are variable: output changes with the weather and time of day. Batteries and other \
			storage systems help by saving extra energy when production is high and releasing it when \
			demand is high. Many countries are increasing the share of renewables in their electricity \
			grids to reduce pollution and dependence on imported fuels.""";

	@Test
	void generatesALessonThatFitsTheSchema() {
		String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.5-flash-lite");
		GeminiProperties properties = new GeminiProperties(System.getenv("GEMINI_API_KEY"), model,
				"https://generativelanguage.googleapis.com", Duration.ofSeconds(10), Duration.ofSeconds(90), 8192, 0.3);
		GeminiService service = new GeminiService(new GeminiConfig().geminiRestClient(properties), properties,
				JsonMapper.builder().build());

		GeneratedLesson lesson = service.generateLesson(new LessonSource("Renewable Energy 101: A Beginner's Guide",
				"Laura Mitchell", "https://example.org/renewable-energy-101", "text", SOURCE));

		System.out.println("---- Gemini " + model + " lesson ----");
		System.out.println("title:     " + lesson.title());
		System.out.println("subject:   " + lesson.subject());
		System.out.println("overview:  " + lesson.overview());
		System.out.println("takeaways: " + lesson.keyTakeaways());
		System.out.println("summary (" + lesson.summary().split("\\s+").length + " words): " + lesson.summary());
		lesson.keyConcepts().forEach(c -> System.out.println("concept:   " + c.term() + " = " + c.explanation()));
		lesson.quiz().forEach(q -> System.out.println("Q: " + q.question() + " " + q.options() + " -> "
				+ q.correctIndex() + " | " + q.explanation()));

		assertThat(lesson.title()).isNotBlank();
		assertThat(lesson.subject()).isIn(LessonPrompt.SUBJECTS);
		assertThat(lesson.keyTakeaways()).hasSizeBetween(3, 5);
		assertThat(lesson.keyConcepts()).hasSizeBetween(4, 6);
		assertThat(lesson.quiz()).hasSize(5).allSatisfy(q -> {
			assertThat(q.options()).hasSize(4);
			assertThat(q.correctIndex()).isBetween(0, 3);
			assertThat(q.explanation()).isNotBlank();
		});
	}

}
