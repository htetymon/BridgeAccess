package com.eduaccess.bridgeaccess.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.eduaccess.bridgeaccess.dto.PackageBundle;
import com.eduaccess.bridgeaccess.dto.PackageSummary;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.repository.CatalogueRepository;
import com.eduaccess.bridgeaccess.repository.CatalogueRepository.BundleHead;
import com.eduaccess.bridgeaccess.repository.CatalogueRepository.FeedQuery;
import com.eduaccess.bridgeaccess.service.FeedCursor;
import com.eduaccess.bridgeaccess.service.PackageService;

import tools.jackson.databind.ObjectMapper;

@WebMvcTest(PackageController.class)
@Import(PackageService.class)
class PackageControllerTest {

	private static final UUID DEVICE = UUID.fromString("11111111-2222-4333-8444-555555555555");

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectMapper objectMapper;

	@MockitoBean
	CatalogueRepository repository;

	// ------------------------------------------------------------ GET /packages

	@Test
	void listDefaultsAndShape() throws Exception {
		given(repository.findFeed(any())).willReturn(List.of(summary(0)));

		mvc.perform(get("/api/v1/packages").header(DeviceIdFilter.HEADER, DEVICE))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].title").value("Package 0"))
			.andExpect(jsonPath("$.items[0].resourceType").value("video"))
			.andExpect(jsonPath("$.items[0].publishedAt").value("2026-11-08T10:15:00.000123Z"))
			.andExpect(jsonPath("$.items[0].isDownloaded").value(false))
			.andExpect(jsonPath("$.items[0].isOwn").value(true))
			.andExpect(jsonPath("$.nextCursor").value(nullValue()));

		FeedQuery query = capturedQuery();
		assertThat(query.deviceId()).isEqualTo(DEVICE);
		assertThat(query.fetchSize()).isEqualTo(21);
		assertThat(query.after()).isNull();
		assertThat(query.excludeMine()).isFalse();
	}

	@Test
	void filtersArePassedThrough() throws Exception {
		given(repository.findFeed(any())).willReturn(List.of());

		mvc.perform(get("/api/v1/packages").header(DeviceIdFilter.HEADER, DEVICE)
			.param("q", "  Solar ")
			.param("subject", "Science")
			.param("resourceType", "text")
			.param("packageType", "with_visual")
			.param("sort", "latest_published")
			.param("excludeMine", "true")
			.param("limit", "3")).andExpect(status().isOk());

		FeedQuery query = capturedQuery();
		assertThat(query.searchText()).isEqualTo("solar");
		assertThat(query.subject()).isEqualTo("Science");
		assertThat(query.resourceType()).isEqualTo("text");
		assertThat(query.packageType()).isEqualTo("with_visual");
		assertThat(query.excludeMine()).isTrue();
		assertThat(query.fetchSize()).isEqualTo(4);
	}

	@Test
	void nextCursorWhenMoreRowsAndItRoundTrips() throws Exception {
		given(repository.findFeed(any()))
			.willReturn(IntStream.range(0, 3).mapToObj(PackageControllerTest::summary).toList());

		MvcResult result = mvc.perform(get("/api/v1/packages").header(DeviceIdFilter.HEADER, DEVICE).param("limit", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andReturn();
		String cursor = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.nextCursor");
		FeedCursor decoded = FeedCursor.decode(cursor, objectMapper);
		assertThat(decoded).isEqualTo(new FeedCursor(summary(1).publishedAt(), summary(1).id()));

		mvc.perform(get("/api/v1/packages").header(DeviceIdFilter.HEADER, DEVICE).param("cursor", cursor))
			.andExpect(status().isOk());
		ArgumentCaptor<FeedQuery> captor = ArgumentCaptor.forClass(FeedQuery.class);
		verify(repository, org.mockito.Mockito.times(2)).findFeed(captor.capture());
		assertThat(captor.getValue().after()).isEqualTo(decoded);
	}

	@Test
	void invalidParamsAreReportedTogether() throws Exception {
		mvc.perform(get("/api/v1/packages").header(DeviceIdFilter.HEADER, DEVICE)
			.param("q", "a")
			.param("subject", "science")
			.param("resourceType", "audio")
			.param("packageType", "visual")
			.param("sort", "popular")
			.param("limit", "51")
			.param("cursor", "garbage!"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.details", hasSize(7)))
			.andExpect(jsonPath("$.error.details[0].field").value("q"))
			.andExpect(jsonPath("$.error.details[0].code").value("TOO_SHORT"))
			.andExpect(jsonPath("$.error.details[6].code").value("INVALID_CURSOR"));
		verify(repository, never()).findFeed(any());
	}

	@Test
	void nonNumericLimitIsValidationFailed() throws Exception {
		mvc.perform(get("/api/v1/packages").header(DeviceIdFilter.HEADER, DEVICE).param("limit", "ten"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.details[0].field").value("limit"));
	}

	@Test
	void missingDeviceIdIsRejected() throws Exception {
		mvc.perform(get("/api/v1/packages"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_DEVICE_ID"));
	}

	// ---------------------------------------------------- GET /packages/{id}/bundle

	@Test
	void bundleShape() throws Exception {
		UUID id = UUID.randomUUID();
		UUID imageId = UUID.randomUUID();
		PackageBundle.Info info = new PackageBundle.Info(id, "Renewable Energy Basics", "Science", "text",
				"with_visual", List.of("summary", "key_concepts", "diagram", "quiz"),
				new PackageBundle.Original("Renewable Energy 101", "Laura Mitchell", "https://example.org/re"),
				850000, Instant.parse("2026-11-08T10:15:00Z"));
		PackageBundle.Content content = new PackageBundle.Content("Overview", List.of("a", "b", "c"), "Summary",
				List.of(new PackageBundle.KeyConcept("Solar power", "Energy from sunlight")));
		given(repository.findBundleHead(id)).willReturn(Optional.of(new BundleHead(info, content)));
		given(repository.findQuestions(id)).willReturn(List.of(new PackageBundle.Question(UUID.randomUUID(), 1,
				"What is renewable energy?", List.of("A", "B", "C", "D"), 0, "Basics", "Because.")));
		given(repository.findImages(id)).willReturn(List.of(new PackageBundle.Image(imageId, "contributor", 1,
				"image/webp", 61240, null, "/api/v1/packages/" + id + "/images/" + imageId)));

		mvc.perform(get("/api/v1/packages/" + id + "/bundle").header(DeviceIdFilter.HEADER, DEVICE))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schemaVersion").value(1))
			.andExpect(jsonPath("$.package.id").value(id.toString()))
			.andExpect(jsonPath("$.package.steps", hasSize(4)))
			.andExpect(jsonPath("$.package.original.author").value("Laura Mitchell"))
			.andExpect(jsonPath("$.package.publishedAt").value("2026-11-08T10:15:00Z"))
			.andExpect(jsonPath("$.content.keyConcepts[0].term").value("Solar power"))
			.andExpect(jsonPath("$.quiz.questions[0].correctIndex").value(0))
			.andExpect(jsonPath("$.quiz.questions[0].options", hasSize(4)))
			.andExpect(jsonPath("$.images[0].url").value("/api/v1/packages/" + id + "/images/" + imageId));
	}

	@Test
	void unknownBundleIsNotFound() throws Exception {
		UUID id = UUID.randomUUID();
		given(repository.findBundleHead(id)).willReturn(Optional.empty());

		mvc.perform(get("/api/v1/packages/" + id + "/bundle").header(DeviceIdFilter.HEADER, DEVICE))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
		verify(repository, never()).findQuestions(any());
	}

	@Test
	void malformedBundleIdIsValidationFailed() throws Exception {
		mvc.perform(get("/api/v1/packages/not-a-uuid/bundle").header(DeviceIdFilter.HEADER, DEVICE))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	// ------------------------------------------------------------------ helpers

	private FeedQuery capturedQuery() {
		ArgumentCaptor<FeedQuery> captor = ArgumentCaptor.forClass(FeedQuery.class);
		verify(repository).findFeed(captor.capture());
		return captor.getValue();
	}

	private static PackageSummary summary(int i) {
		return new PackageSummary(UUID.nameUUIDFromBytes(("pkg" + i).getBytes()), "Package " + i, "Science", "video",
				"text_only", 1000 + i, Instant.parse("2026-11-08T10:15:00Z").minusSeconds(i * 60L).plusNanos(123_000),
				false, true);
	}

}
