package com.eduaccess.bridgeaccess.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.eduaccess.bridgeaccess.dto.MyPackage;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.repository.ContributionRepository;
import com.eduaccess.bridgeaccess.repository.ContributionRepository.LinkOwner;
import com.eduaccess.bridgeaccess.repository.MyPackageRepository;
import com.eduaccess.bridgeaccess.repository.MyPackageRepository.OwnedPackage;
import com.eduaccess.bridgeaccess.repository.MyPackageRepository.PageQuery;
import com.eduaccess.bridgeaccess.config.ContributionConfig.ContributionProperties;
import com.eduaccess.bridgeaccess.service.DeletedPackageCleaner;
import com.eduaccess.bridgeaccess.service.MyPackageService;
import com.eduaccess.bridgeaccess.service.generation.GenerationDispatcher;
import com.eduaccess.bridgeaccess.storage.ImageStore;

@WebMvcTest(MyPackageController.class)
@Import({ MyPackageService.class, DeletedPackageCleaner.class, MyPackageControllerTest.Config.class })
class MyPackageControllerTest {

	private static final UUID DEVICE = UUID.fromString("11111111-2222-4333-8444-555555555555");

	private static final UUID OTHER_DEVICE = UUID.fromString("99999999-2222-4333-8444-555555555555");

	private static final UUID ID = UUID.fromString("0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10");

	private static final UUID OTHER_PACKAGE = UUID.fromString("7c1d2e3f-4a5b-4c6d-8e7f-901a2b3c4d5e");

	private static final String LINK = "https://example.org/renewable-energy-101";

	@TestConfiguration
	static class Config {

		@Bean
		ContributionProperties contributionProperties() {
			return new ContributionProperties(5, 6);
		}

	}

	@Autowired
	MockMvc mvc;

	@MockitoBean
	MyPackageRepository repository;

	@MockitoBean
	ContributionRepository contributionRepository;

	@MockitoBean
	GenerationDispatcher dispatcher;

	@MockitoBean
	ImageStore imageStore;

	// ------------------------------------------------------------------ list

	@Test
	void listsThisDevicesPackagesNewestFirstWithACursor() throws Exception {
		UUID second = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d");
		UUID third = UUID.fromString("2b3c4d5e-6f7a-4b2c-9d3e-4f5a6b7c8d9e");
		given(this.repository.findPage(any())).willReturn(List.of(view(ID, "published", null, CREATED),
				view(second, "failed", "x", CREATED.minusSeconds(60)), view(third, "processing", null, CREATED.minusSeconds(120))));

		String cursor = new JSONObject(as(DEVICE, get("/api/v1/me/packages?limit=2")).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-cache, private"))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].id").value(ID.toString()))
			.andExpect(jsonPath("$.items[0].title").value("Clean Energy Basics"))
			.andExpect(jsonPath("$.items[1].status").value("failed"))
			.andExpect(jsonPath("$.nextCursor").isString())
			.andReturn()
			.getResponse()
			.getContentAsString()).getString("nextCursor");

		ArgumentCaptor<PageQuery> first = ArgumentCaptor.forClass(PageQuery.class);
		verify(this.repository).findPage(first.capture());
		assertThat(first.getValue()).isEqualTo(new PageQuery(DEVICE, null, null, null, 3));

		given(this.repository.findPage(any())).willReturn(List.of(view(third, "processing", null, CREATED.minusSeconds(120))));
		as(DEVICE, get("/api/v1/me/packages?limit=2&status=processing&cursor=" + cursor)).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());

		ArgumentCaptor<PageQuery> next = ArgumentCaptor.forClass(PageQuery.class);
		verify(this.repository, org.mockito.Mockito.times(2)).findPage(next.capture());
		assertThat(next.getValue())
			.isEqualTo(new PageQuery(DEVICE, "processing", CREATED.minusSeconds(60), second, 3));
	}

	@Test
	void listUsesDefaultLimitOfTwenty() throws Exception {
		given(this.repository.findPage(any())).willReturn(List.of());

		as(DEVICE, get("/api/v1/me/packages")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());

		verify(this.repository).findPage(new PageQuery(DEVICE, null, null, null, 21));
	}

	@Test
	void listRejectsBadFiltersWithFieldDetails() throws Exception {
		as(DEVICE, get("/api/v1/me/packages?status=deleted&limit=51&cursor=nope")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.details[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("status",
					"limit", "cursor")));
		verifyNoInteractions(this.repository);
	}

	// ------------------------------------------------------------------ status

	@Test
	void returnsProcessingPackageWithNullTitleAndSubject() throws Exception {
		owned("processing", null);

		as(DEVICE, get("/api/v1/me/packages/" + ID)).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-cache, private"))
			.andExpect(jsonPath("$.id").value(ID.toString()))
			.andExpect(jsonPath("$.status").value("processing"))
			.andExpect(jsonPath("$.title").doesNotExist())
			.andExpect(jsonPath("$.subject").doesNotExist())
			.andExpect(jsonPath("$.originalTitle").value("Renewable Energy 101"))
			.andExpect(jsonPath("$.failureReason").doesNotExist())
			.andExpect(jsonPath("$.isLearnt").value(false))
			.andExpect(jsonPath("$.createdAt").value("2026-11-13T15:30:00Z"));
	}

	@Test
	void returnsFailureReasonOfFailedPackage() throws Exception {
		owned("failed", "This took too long. Please try again.");

		as(DEVICE, get("/api/v1/me/packages/" + ID)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("failed"))
			.andExpect(jsonPath("$.failureReason").value("This took too long. Please try again."));
	}

	@Test
	void anotherDevicesPackageIsNotFound() throws Exception {
		owned("published", null); // stubbed for DEVICE only

		as(OTHER_DEVICE, get("/api/v1/me/packages/" + ID)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void requiresDeviceId() throws Exception {
		this.mvc.perform(get("/api/v1/me/packages/" + ID)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_DEVICE_ID"));
		verifyNoInteractions(this.repository);
	}

	@Test
	void malformedIdIsNotAServerError() throws Exception {
		as(DEVICE, get("/api/v1/me/packages/not-a-uuid")).andExpect(status().is4xxClientError());
	}

	// ------------------------------------------------------------------ retry

	@Test
	void retriesFailedPackage() throws Exception {
		owned("failed", "This took too long. Please try again.");
		given(this.repository.resetForRetry(DEVICE, ID, 6)).willReturn(true);

		as(DEVICE, post("/api/v1/me/packages/" + ID + "/retry")).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.id").value(ID.toString()))
			.andExpect(jsonPath("$.status").value("processing"));

		verify(this.dispatcher).submit(ID);
	}

	@Test
	void retryOfPackageThatIsNotFailedIsRefused() throws Exception {
		for (String status : new String[] { "processing", "published" }) {
			owned(status, null);

			as(DEVICE, post("/api/v1/me/packages/" + ID + "/retry")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("NOT_RETRYABLE"));
		}
		verify(this.repository, never()).resetForRetry(any(), any(), anyInt());
		verifyNoInteractions(this.dispatcher);
	}

	@Test
	void retryRefusedOnceTheAiBudgetIsUsed() throws Exception {
		owned("failed", "We couldn't build a reliable lesson from this. Please try again.", 6);

		as(DEVICE, post("/api/v1/me/packages/" + ID + "/retry")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("NOT_RETRYABLE"))
			.andExpect(jsonPath("$.error.message").value("This package can't be retried again. Please create it again."));
		verify(this.repository, never()).resetForRetry(any(), any(), anyInt());
		verifyNoInteractions(this.dispatcher);
	}

	@Test
	void retryAllowedJustUnderTheBudget() throws Exception {
		owned("failed", "x", 5);
		given(this.repository.resetForRetry(DEVICE, ID, 6)).willReturn(true);

		as(DEVICE, post("/api/v1/me/packages/" + ID + "/retry")).andExpect(status().isAccepted());
		verify(this.dispatcher).submit(ID);
	}

	@Test
	void retryOfAnotherDevicesPackageIsNotFound() throws Exception {
		owned("failed", "x");

		as(OTHER_DEVICE, post("/api/v1/me/packages/" + ID + "/retry")).andExpect(status().isNotFound());
		verify(this.repository, never()).resetForRetry(any(), any(), anyInt());
	}

	@Test
	void retryRacingAnotherRetryIsRefused() throws Exception {
		owned("failed", "x");
		given(this.repository.resetForRetry(DEVICE, ID, 6)).willReturn(false);

		as(DEVICE, post("/api/v1/me/packages/" + ID + "/retry")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("NOT_RETRYABLE"));
		verifyNoInteractions(this.dispatcher);
	}

	@Test
	void retryBlockedByANewerPackageWithTheSameLink() throws Exception {
		owned("failed", "x");
		given(this.repository.resetForRetry(DEVICE, ID, 6)).willThrow(new DuplicateKeyException("uq_lp_live_link"));
		given(this.contributionRepository.findLiveByLink(LINK))
			.willReturn(Optional.of(new LinkOwner(OTHER_PACKAGE, OTHER_DEVICE)));

		as(DEVICE, post("/api/v1/me/packages/" + ID + "/retry")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_LINK"))
			.andExpect(jsonPath("$.error.existingPackageId").value(OTHER_PACKAGE.toString()))
			.andExpect(jsonPath("$.error.isOwn").value(false));
		verifyNoInteractions(this.dispatcher);
	}

	// ------------------------------------------------------------------ delete

	@Test
	void deletesPackageAndItsFiles() throws Exception {
		given(this.repository.softDelete(DEVICE, ID)).willReturn(true);
		given(this.imageStore.deleteFolder(ID.toString())).willReturn(2);

		as(DEVICE, delete("/api/v1/me/packages/" + ID)).andExpect(status().isNoContent());

		verify(this.imageStore).deleteFolder(ID.toString());
		verify(this.repository).deleteImageRows(ID); // files gone, so the cleanup marker goes too
	}

	@Test
	void deletingAnotherDevicesPackageIsNotFoundAndTouchesNoFiles() throws Exception {
		given(this.repository.softDelete(OTHER_DEVICE, ID)).willReturn(false);

		as(OTHER_DEVICE, delete("/api/v1/me/packages/" + ID)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

		verify(this.imageStore, never()).deleteFolder(anyString());
	}

	@Test
	void storageOutageStillDeletesThePackage() throws Exception {
		given(this.repository.softDelete(DEVICE, ID)).willReturn(true);
		given(this.imageStore.deleteFolder(ID.toString())).willThrow(new RuntimeException("storage down"));

		as(DEVICE, delete("/api/v1/me/packages/" + ID)).andExpect(status().isNoContent());
		verify(this.repository, never()).deleteImageRows(any()); // rows stay, so the sweep retries
	}

	// ------------------------------------------------------------------ helpers

	private void owned(String status, String failureReason) {
		owned(status, failureReason, 2);
	}

	private void owned(String status, String failureReason, int attempts) {
		given(this.repository.findOwned(DEVICE, ID))
			.willReturn(Optional.of(new OwnedPackage(view(ID, status, failureReason, CREATED), LINK, attempts)));
	}

	private static final Instant CREATED = Instant.parse("2026-11-13T15:30:00Z");

	private static MyPackage view(UUID id, String status, String failureReason, Instant createdAt) {
		boolean published = "published".equals(status);
		return new MyPackage(id, published ? "Clean Energy Basics" : null, "Renewable Energy 101",
				published ? "Science" : null, "text", "text_only", status, failureReason, published ? 31_000 : 0,
				createdAt, published ? createdAt.plusSeconds(70) : null, false);
	}

	private org.springframework.test.web.servlet.ResultActions as(UUID device, MockHttpServletRequestBuilder request)
			throws Exception {
		return this.mvc.perform(request.header(DeviceIdFilter.HEADER, device.toString()));
	}

}
