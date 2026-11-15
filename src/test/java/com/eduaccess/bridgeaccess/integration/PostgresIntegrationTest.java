package com.eduaccess.bridgeaccess.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.service.GeminiService;
import com.eduaccess.bridgeaccess.service.GeneratedLesson;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for integration tests: the whole application against a real PostgreSQL 17 (embedded,
 * no Docker) loaded with {@code bridgeaccess_schema_full.sql}, so the SQL, constraints and
 * triggers the unit tests mock are exercised for real. Requests go through MockMvc with all
 * filters. Only Gemini is replaced (it is a paid external API); images are stored in a
 * temporary folder.
 *
 * <p>
 * One database serves every integration test class (started once per JVM); each test starts
 * from empty tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class PostgresIntegrationTest {

	static final String DEVICE = "11111111-2222-4333-8444-555555555555";

	static final String OTHER_DEVICE = "99999999-2222-4333-8444-555555555555";

	private static final EmbeddedPostgres POSTGRES = Database.start();

	private static final Path IMAGES = Database.tempDir();

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
		registry.add("spring.datasource.username", () -> "postgres");
		registry.add("spring.datasource.password", () -> "postgres");
		registry.add("bridgeaccess.storage.supabase-url", () -> "");
		registry.add("bridgeaccess.storage.secret-key", () -> "");
		registry.add("bridgeaccess.storage.local-dir", IMAGES::toString);
		// Any key: the client is replaced below, this only keeps "not configured" checks quiet.
		registry.add("bridgeaccess.gemini.api-key", () -> "test-key");
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@MockitoBean
	GeminiService gemini;

	@BeforeEach
	void emptyTablesAndFakeGemini() {
		this.jdbc.execute("""
				truncate app.learning_packages, app.quiz_attempts, app.downloads, app.bookmarks,
				         app.learning_progress cascade""");
		given(this.gemini.generateLesson(any(), any())).willReturn(lesson());
		given(this.gemini.generateLesson(any())).willReturn(lesson());
	}

	// ------------------------------------------------------------------ helpers

	<B extends AbstractMockHttpServletRequestBuilder<B>> B as(String device, B request) {
		return request.header(DeviceIdFilter.HEADER, device);
	}

	JsonNode json(RequestBuilder request, int expectedStatus) throws Exception {
		String body = this.mvc.perform(request)
			.andExpect(status().is(expectedStatus))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		return body.isEmpty() ? null : this.objectMapper.readTree(body);
	}

	/** Contributes a text package with one photo, as the web app does (POST /packages, multipart). */
	UUID contribute(String device) throws Exception {
		Map<String, Object> data = Map.of("resourceType", "text", "originalTitle", "Renewable Energy 101", "author",
				"Laura Mitchell", "resourceLink", "https://example.org/renewable-energy-" + UUID.randomUUID(), "content",
				SOURCE_TEXT, "imageAltTexts", List.of("Wind turbines on a hill"));
		MockMultipartFile dataPart = new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
				this.objectMapper.writeValueAsBytes(data));
		MockMultipartFile image = new MockMultipartFile("images", "turbines.png", MediaType.IMAGE_PNG_VALUE, png());
		JsonNode created = json(as(device, multipart("/api/v1/packages").file(dataPart).file(image)), 202);
		assertThat(created.get("status").asString()).isEqualTo("processing");
		return UUID.fromString(created.get("id").asString());
	}

	/** Polls the contributor's status endpoint, as the app does, until the job is done. */
	JsonNode awaitStatus(String device, UUID packageId, String wanted) throws Exception {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
		JsonNode row;
		do {
			row = json(as(device, get("/api/v1/me/packages/" + packageId)), 200);
			if (wanted.equals(row.get("status").asString())) {
				return row;
			}
			if ("failed".equals(row.get("status").asString())) {
				throw new AssertionError("Package failed: " + row.get("failureReason"));
			}
			Thread.sleep(100);
		}
		while (Instant.now().isBefore(deadline));
		throw new AssertionError("Package still " + row.get("status") + " after 30 s");
	}

	UUID publishedPackage() throws Exception {
		UUID id = contribute(OTHER_DEVICE);
		awaitStatus(OTHER_DEVICE, id, "published");
		return id;
	}

	/** A small real photo, so image optimisation runs for real. */
	static byte[] png() {
		BufferedImage image = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(40, 140, 220));
		g.fillRect(0, 0, 320, 200);
		g.setColor(Color.WHITE);
		g.fillOval(200, 20, 60, 60);
		g.dispose();
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static final String SOURCE_TEXT = "Renewable energy comes from sources that replenish naturally, such as sunlight, "
			.repeat(12);

	/** A lesson that passes LessonValidator, as Gemini would return it. */
	static GeneratedLesson lesson() {
		String summary = String.join(" ", IntStream.range(0, 100).mapToObj(i -> "energy").toList());
		List<GeneratedLesson.QuizQuestion> quiz = IntStream.range(0, 5)
			.mapToObj(i -> new GeneratedLesson.QuizQuestion("Question " + (i + 1) + " about renewable energy?",
					List.of("Option A" + i, "Option B" + i, "Option C" + i, "Option D" + i), i % 4,
					"Because of reason " + (i + 1) + "."))
			.toList();
		return new GeneratedLesson("Renewable Energy Basics", "Science",
				"A beginner-friendly introduction to renewable energy.",
				List.of("Understand renewable energy", "Explore common types", "Learn its benefits"), summary,
				List.of(new GeneratedLesson.KeyConcept("Solar power", "Energy captured from sunlight."),
						new GeneratedLesson.KeyConcept("Wind power", "Energy from moving air."),
						new GeneratedLesson.KeyConcept("Hydropower", "Energy from flowing water."),
						new GeneratedLesson.KeyConcept("Geothermal", "Heat from inside the Earth.")),
				quiz);
	}

	/** Starts PostgreSQL once and loads the schema the way it is deployed on Supabase. */
	static final class Database {

		private Database() {
		}

		static EmbeddedPostgres start() {
			try {
				EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
				Runtime.getRuntime().addShutdownHook(new Thread(() -> {
					try {
						postgres.close();
					}
					catch (IOException ignored) {
						// shutting down anyway
					}
				}));
				String schema = Files.readString(Path.of("bridgeaccess_schema_full.sql"), StandardCharsets.UTF_8);
				try (Connection connection = postgres.getPostgresDatabase().getConnection();
						Statement statement = connection.createStatement()) {
					// What Supabase provides before migrations run: its roles and the extensions schema.
					statement.execute("""
							create role anon nologin;
							create role authenticated nologin;
							create role service_role nologin;
							create schema extensions;
							""");
					// One statement: the driver sends it as a script, so $$ function bodies stay intact.
					statement.execute(schema);
				}
				return postgres;
			}
			catch (Exception ex) {
				throw new IllegalStateException("Could not start the integration test database", ex);
			}
		}

		static Path tempDir() {
			try {
				Path dir = Files.createTempDirectory("bridgeaccess-images-");
				dir.toFile().deleteOnExit();
				return dir;
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

	}

}
