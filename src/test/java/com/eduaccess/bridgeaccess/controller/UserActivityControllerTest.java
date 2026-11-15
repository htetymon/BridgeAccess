package com.eduaccess.bridgeaccess.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.eduaccess.bridgeaccess.dto.BookmarkView;
import com.eduaccess.bridgeaccess.dto.DownloadView;
import com.eduaccess.bridgeaccess.dto.PackageSummary;
import com.eduaccess.bridgeaccess.dto.ProgressView;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.repository.BookmarkRepository;
import com.eduaccess.bridgeaccess.repository.BookmarkRepository.AddResult;
import com.eduaccess.bridgeaccess.repository.DownloadRepository;
import com.eduaccess.bridgeaccess.repository.LearningProgressRepository;
import com.eduaccess.bridgeaccess.service.BookmarkService;
import com.eduaccess.bridgeaccess.service.DownloadRecordService;
import com.eduaccess.bridgeaccess.service.ProgressService;

@WebMvcTest(UserActivityController.class)
@Import({ ProgressService.class, DownloadRecordService.class, BookmarkService.class })
class UserActivityControllerTest {

	private static final UUID DEVICE = UUID.fromString("11111111-2222-4333-8444-555555555555");

	private static final UUID PKG = UUID.fromString("0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10");

	private static final UUID PKG2 = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d");

	private static final Instant T = Instant.parse("2026-11-10T09:12:00Z");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	LearningProgressRepository progress;

	@MockitoBean
	DownloadRepository downloads;

	@MockitoBean
	BookmarkRepository bookmarks;

	// ------------------------------------------------------------------ progress

	@Test
	void progressIsMergedWithSortedDeduplicatedSteps() throws Exception {
		given(this.progress.upsert(any(), any(), anyString(), any(), any())).willReturn(Optional
			.of(new ProgressView(PKG, List.of("key_concepts", "summary"), "diagram", T, false, null)));

		as(put("/api/v1/me/progress/" + PKG).contentType(MediaType.APPLICATION_JSON).content("""
				{"completedSteps":["summary","key_concepts","summary"],"lastStep":"diagram","lastOpenedAt":"2026-11-10T09:12:00Z"}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.packageId").value(PKG.toString()))
			.andExpect(jsonPath("$.completedSteps[0]").value("key_concepts"))
			.andExpect(jsonPath("$.lastStep").value("diagram"))
			.andExpect(jsonPath("$.isLearnt").value(false))
			.andExpect(jsonPath("$.learntAt").doesNotExist());

		verify(this.progress).upsert(DEVICE, PKG, "{key_concepts,summary}", "diagram", T);
	}

	@Test
	void progressWithNothingButATouchKeepsTheResumePoint() throws Exception {
		given(this.progress.upsert(any(), any(), anyString(), any(), any()))
			.willReturn(Optional.of(new ProgressView(PKG, List.of(), null, T, false, null)));

		as(put("/api/v1/me/progress/" + PKG).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk());

		verify(this.progress).upsert(eq(DEVICE), eq(PKG), eq("{}"), isNull(), isNull());
	}

	@Test
	void unknownStepsAreRejectedWithoutTouchingTheDatabase() throws Exception {
		as(put("/api/v1/me/progress/" + PKG).contentType(MediaType.APPLICATION_JSON)
			.content("{\"completedSteps\":[\"summary\",\"learnt\"],\"lastStep\":\"intro\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.details[0].field").value("completedSteps"))
			.andExpect(jsonPath("$.error.details[1].field").value("lastStep"));
		verifyNoInteractions(this.progress);
	}

	@Test
	void progressForAPackageThatWasNeverPublishedIs404() throws Exception {
		given(this.progress.upsert(any(), any(), anyString(), any(), any())).willReturn(Optional.empty());

		as(put("/api/v1/me/progress/" + PKG).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void progressNeedsADeviceId() throws Exception {
		this.mvc.perform(put("/api/v1/me/progress/" + PKG).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_DEVICE_ID"));
		verifyNoInteractions(this.progress);
	}

	@Test
	void oneProgressRowOr404() throws Exception {
		given(this.progress.find(DEVICE, PKG))
			.willReturn(Optional.of(new ProgressView(PKG, List.of("summary"), "summary", T, true, T)));
		as(get("/api/v1/me/progress/" + PKG)).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-cache, private"))
			.andExpect(jsonPath("$.isLearnt").value(true))
			.andExpect(jsonPath("$.learntAt").value("2026-11-10T09:12:00Z"));

		as(get("/api/v1/me/progress/" + PKG2)).andExpect(status().isNotFound());
	}

	@Test
	void progressListPagesWithACursorOnLastOpenedAt() throws Exception {
		given(this.progress.findPage(eq(DEVICE), isNull(), isNull(), eq(2)))
			.willReturn(List.of(new ProgressView(PKG, List.of(), null, T, false, null),
					new ProgressView(PKG2, List.of(), null, T.minusSeconds(60), false, null)));

		String cursor = new JSONObject(as(get("/api/v1/me/progress?limit=1")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].packageId").value(PKG.toString()))
			.andReturn()
			.getResponse()
			.getContentAsString()).getString("nextCursor");

		given(this.progress.findPage(DEVICE, T, PKG, 2)).willReturn(List.of());
		as(get("/api/v1/me/progress?limit=1&cursor=" + cursor)).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
		verify(this.progress).findPage(DEVICE, T, PKG, 2);
	}

	@Test
	void badLimitAndCursorAreValidationErrors() throws Exception {
		as(get("/api/v1/me/progress?limit=51&cursor=nope")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].field").value("limit"))
			.andExpect(jsonPath("$.error.details[1].field").value("cursor"));
	}

	// ----------------------------------------------------------------- downloads

	@Test
	void downloadIsRecorded() throws Exception {
		given(this.downloads.upsert(any(), any(), anyString(), anyInt(), any()))
			.willReturn(Optional.of(new DownloadView(PKG, "completed", 64_740, T)));

		as(put("/api/v1/me/downloads/" + PKG).contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"completed\",\"sizeBytes\":64740,\"downloadedAt\":\"2026-11-10T09:12:00Z\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.packageId").value(PKG.toString()))
			.andExpect(jsonPath("$.status").value("completed"))
			.andExpect(jsonPath("$.sizeBytes").value(64_740))
			.andExpect(jsonPath("$.downloadedAt").value("2026-11-10T09:12:00Z"));

		verify(this.downloads).upsert(DEVICE, PKG, "completed", 64_740, T);
	}

	@Test
	void downloadBodyIsValidated() throws Exception {
		as(put("/api/v1/me/downloads/" + PKG).contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"done\",\"sizeBytes\":-1}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].field").value("status"))
			.andExpect(jsonPath("$.error.details[1].field").value("sizeBytes"));
		as(put("/api/v1/me/downloads/" + PKG).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details.length()").value(2));
		verifyNoInteractions(this.downloads);
	}

	@Test
	void downloadOfAnUnavailablePackageIs404() throws Exception {
		given(this.downloads.upsert(any(), any(), anyString(), anyInt(), any())).willReturn(Optional.empty());

		as(put("/api/v1/me/downloads/" + PKG).contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"completed\",\"sizeBytes\":1}"))
			.andExpect(status().isNotFound());
	}

	@Test
	void removingADownloadIsAlways204() throws Exception {
		as(delete("/api/v1/me/downloads/" + PKG)).andExpect(status().isNoContent());
		as(delete("/api/v1/me/downloads/" + PKG)).andExpect(status().isNoContent());
		verify(this.downloads, org.mockito.Mockito.times(2)).markRemoved(DEVICE, PKG);
	}

	@Test
	void activeDownloadsAreListed() throws Exception {
		given(this.downloads.findPage(DEVICE, null, null, 21))
			.willReturn(List.of(new DownloadView(PKG, "completed", 100, T)));
		as(get("/api/v1/me/downloads")).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-cache, private"))
			.andExpect(jsonPath("$.items[0].packageId").value(PKG.toString()))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	// ----------------------------------------------------------------- bookmarks

	@Test
	void bookmarkingIsIdempotent() throws Exception {
		given(this.bookmarks.add(DEVICE, PKG)).willReturn(AddResult.ADDED, AddResult.ALREADY_BOOKMARKED);
		as(put("/api/v1/me/bookmarks/" + PKG)).andExpect(status().isNoContent());
		as(put("/api/v1/me/bookmarks/" + PKG)).andExpect(status().isNoContent());
	}

	@Test
	void bookmarkingAnUnavailablePackageIs404() throws Exception {
		given(this.bookmarks.add(DEVICE, PKG)).willReturn(AddResult.NOT_AVAILABLE);
		as(put("/api/v1/me/bookmarks/" + PKG)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void removingABookmarkIsAlways204() throws Exception {
		as(delete("/api/v1/me/bookmarks/" + PKG)).andExpect(status().isNoContent());
		verify(this.bookmarks).remove(DEVICE, PKG);
	}

	@Test
	void bookmarksListCarriesThePackageCard() throws Exception {
		PackageSummary card = new PackageSummary(PKG, "Renewable Energy Basics", "Science", "video", "with_visual",
				850_000, T, true, false);
		PackageSummary card2 = new PackageSummary(PKG2, "Other", "Math", "text", "text_only", 1, T, false, true);
		given(this.bookmarks.findPage(DEVICE, null, null, 2))
			.willReturn(List.of(new BookmarkView(T, card), new BookmarkView(T.minusSeconds(5), card2)));

		String cursor = new JSONObject(as(get("/api/v1/me/bookmarks?limit=1")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].bookmarkedAt").value("2026-11-10T09:12:00Z"))
			.andExpect(jsonPath("$.items[0].package.id").value(PKG.toString()))
			.andExpect(jsonPath("$.items[0].package.title").value("Renewable Energy Basics"))
			.andExpect(jsonPath("$.items[0].package.isDownloaded").value(true))
			.andExpect(jsonPath("$.items[0].package.isOwn").value(false))
			.andReturn()
			.getResponse()
			.getContentAsString()).getString("nextCursor");

		given(this.bookmarks.findPage(DEVICE, T, PKG, 2)).willReturn(List.of());
		as(get("/api/v1/me/bookmarks?limit=1&cursor=" + cursor)).andExpect(status().isOk());
		verify(this.bookmarks).findPage(DEVICE, T, PKG, 2);
		assertThat(cursor).isNotBlank();
	}

	private ResultActions as(MockHttpServletRequestBuilder request) throws Exception {
		return this.mvc.perform(request.header(DeviceIdFilter.HEADER, DEVICE.toString()));
	}

}
