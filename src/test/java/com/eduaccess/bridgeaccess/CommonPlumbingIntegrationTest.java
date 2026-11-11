package com.eduaccess.bridgeaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import com.eduaccess.bridgeaccess.config.HttpCachingConfig;
import com.eduaccess.bridgeaccess.config.WebConfig;
import com.eduaccess.bridgeaccess.error.ApiErrorController;
import com.eduaccess.bridgeaccess.error.ApiException;
import com.eduaccess.bridgeaccess.error.ErrorCode;
import com.eduaccess.bridgeaccess.error.GlobalExceptionHandler;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.filter.RequestIdFilter;

/**
 * Exercises error envelope, gzip, and ETag/304 against a real embedded Tomcat. The database
 * auto-configuration is excluded so this runs without Supabase.
 */
@SpringBootTest(classes = CommonPlumbingIntegrationTest.TestApp.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommonPlumbingIntegrationTest {

	private static final String DEVICE = "11111111-2222-4333-8444-555555555555";

	private final HttpClient http = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	int port;

	@Test
	void largeJsonIsGzippedWhenRequested() throws Exception {
		HttpResponse<byte[]> res = send(get("/api/v1/things").header("Accept-Encoding", "gzip"));
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.headers().firstValue("Content-Encoding")).hasValue("gzip");
		String body = gunzip(res.body());
		assertThat(body).startsWith("[{\"name\":\"thing-0\"");
	}

	@Test
	void notGzippedWithoutAcceptEncoding() throws Exception {
		HttpResponse<byte[]> res = send(get("/api/v1/things"));
		assertThat(res.headers().firstValue("Content-Encoding")).isEmpty();
	}

	@Test
	void etagAndNotModified() throws Exception {
		HttpResponse<byte[]> first = send(get("/api/v1/things").header("Accept-Encoding", "gzip"));
		String etag = first.headers().firstValue("ETag").orElseThrow();
		assertThat(etag).startsWith("W/\"");

		HttpResponse<byte[]> second = send(
				get("/api/v1/things").header("Accept-Encoding", "gzip").header("If-None-Match", etag));
		assertThat(second.statusCode()).isEqualTo(304);
		assertThat(second.body()).isEmpty();

		HttpResponse<byte[]> stale = send(get("/api/v1/things").header("If-None-Match", "W/\"0stale\""));
		assertThat(stale.statusCode()).isEqualTo(200);
	}

	@Test
	void requestIdIsEchoed() throws Exception {
		String id = UUID.randomUUID().toString();
		HttpResponse<byte[]> res = send(get("/api/v1/things").header("X-Request-Id", id));
		assertThat(res.headers().firstValue("X-Request-Id")).hasValue(id);
	}

	@Test
	void validationErrorHasDetails() throws Exception {
		HttpResponse<byte[]> res = send(base("/api/v1/things").header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"\"}")));
		assertThat(res.statusCode()).isEqualTo(400);
		String body = text(res);
		assertThat(body).contains("\"code\":\"VALIDATION_FAILED\"")
			.contains("\"field\":\"name\"")
			.contains("\"code\":\"NOT_BLANK\"")
			.contains("\"requestId\":\"" + res.headers().firstValue("X-Request-Id").orElseThrow() + "\"");
	}

	@Test
	void malformedJsonIsValidationFailed() throws Exception {
		HttpResponse<byte[]> res = send(base("/api/v1/things").header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{nope")));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(text(res)).contains("\"code\":\"VALIDATION_FAILED\"").doesNotContain("details");
	}

	@Test
	void badUuidPathVariable() throws Exception {
		HttpResponse<byte[]> res = send(get("/api/v1/things/not-a-uuid"));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(text(res)).contains("\"field\":\"id\"").contains("INVALID_FORMAT");
	}

	@Test
	void apiExceptionMapsToItsStatus() throws Exception {
		HttpResponse<byte[]> res = send(get("/api/v1/things/" + UUID.randomUUID()));
		assertThat(res.statusCode()).isEqualTo(404);
		assertThat(text(res)).contains("\"code\":\"NOT_FOUND\"");
	}

	@Test
	void unknownRouteIsNotFoundEnvelope() throws Exception {
		HttpResponse<byte[]> res = send(get("/api/v1/does-not-exist"));
		assertThat(res.statusCode()).isEqualTo(404);
		assertThat(text(res)).contains("\"code\":\"NOT_FOUND\"");
	}

	@Test
	void wrongMethodIsEnvelope() throws Exception {
		HttpResponse<byte[]> res = send(base("/api/v1/boom").DELETE());
		assertThat(res.statusCode()).isEqualTo(405);
		assertThat(text(res)).contains("\"code\":\"METHOD_NOT_ALLOWED\"");
	}

	@Test
	void unexpectedExceptionIsInternalErrorWithoutLeak() throws Exception {
		HttpResponse<byte[]> res = send(get("/api/v1/boom"));
		assertThat(res.statusCode()).isEqualTo(500);
		assertThat(text(res)).contains("\"code\":\"INTERNAL_ERROR\"").doesNotContain("secret");
	}

	@Test
	void exceptionInFilterUsesEnvelopeViaErrorController() throws Exception {
		HttpResponse<byte[]> res = send(get("/api/v1/filter-boom"));
		assertThat(res.statusCode()).isEqualTo(503);
		assertThat(text(res)).contains("\"code\":\"SERVICE_UNAVAILABLE\"").contains("\"requestId\"");
	}

	@Test
	void missingDeviceIdStillUsesEnvelope() throws Exception {
		HttpResponse<byte[]> res = send(base("/api/v1/things").GET());
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(text(res)).contains("\"code\":\"INVALID_DEVICE_ID\"");
		assertThat(res.headers().firstValue("X-Request-Id")).isPresent();
	}

	// ------------------------------------------------------------------ helpers

	private HttpRequest.Builder base(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
	}

	private HttpRequest.Builder get(String path) {
		return base(path).header(DeviceIdFilter.HEADER, DEVICE).GET();
	}

	private HttpResponse<byte[]> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
		HttpRequest request = builder.build();
		if (!request.headers().firstValue(DeviceIdFilter.HEADER).isPresent() && !"GET".equals(request.method())) {
			request = HttpRequest.newBuilder(request, (n, v) -> true).header(DeviceIdFilter.HEADER, DEVICE).build();
		}
		return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
	}

	private static String text(HttpResponse<byte[]> res) throws IOException {
		return res.headers().firstValue("Content-Encoding").isPresent() ? gunzip(res.body())
				: new String(res.body(), StandardCharsets.UTF_8);
	}

	private static String gunzip(byte[] bytes) throws IOException {
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	// ------------------------------------------------------------------ fixture

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(excludeName = { "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
			"org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration",
			"org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
			"org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration" })
	@Import({ WebConfig.class, HttpCachingConfig.class, RequestIdFilter.class, DeviceIdFilter.class,
			GlobalExceptionHandler.class, ApiErrorController.class, StubController.class })
	static class TestApp {

		@Bean
		FilterRegistrationBean<OncePerRequestFilter> failingFilter() {
			OncePerRequestFilter filter = new OncePerRequestFilter() {
				@Override
				protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
						FilterChain chain) throws ServletException, IOException {
					if (request.getRequestURI().endsWith("/filter-boom")) {
						throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
					}
					chain.doFilter(request, response);
				}
			};
			FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
			registration.setOrder(0);
			return registration;
		}

	}

	record NewThing(@NotBlank @Size(max = 10) String name) {
	}

	record Thing(String name, String description) {
	}

	@RestController
	static class StubController {

		@GetMapping("/things")
		List<Thing> list() {
			return IntStream.range(0, 50)
				.mapToObj(i -> new Thing("thing-" + i, "A reasonably long description for item " + i))
				.toList();
		}

		@GetMapping("/things/{id}")
		Thing one(@PathVariable UUID id) {
			throw ApiException.notFound();
		}

		@PostMapping("/things")
		Thing create(@Valid @RequestBody NewThing body) {
			return new Thing(body.name(), "");
		}

		@GetMapping("/boom")
		Thing boom() {
			throw new IllegalStateException("secret internal detail");
		}

	}

}
