package com.eduaccess.bridgeaccess.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionOperations;

import com.eduaccess.bridgeaccess.config.ContributionConfig.ContributionProperties;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.repository.ContributionRepository;
import com.eduaccess.bridgeaccess.repository.ContributionRepository.LinkOwner;
import com.eduaccess.bridgeaccess.repository.ContributionRepository.NewImageRow;
import com.eduaccess.bridgeaccess.repository.ContributionRepository.NewPackageRow;
import com.eduaccess.bridgeaccess.service.ContributionService;
import com.eduaccess.bridgeaccess.service.CreatePackageValidator;
import com.eduaccess.bridgeaccess.service.generation.GenerationDispatcher;
import com.eduaccess.bridgeaccess.storage.ImageStore;

import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ContributionController.class)
@Import({ ContributionService.class, CreatePackageValidator.class, ContributionControllerTest.Config.class })
class ContributionControllerTest {

	private static final UUID DEVICE = UUID.fromString("11111111-2222-4333-8444-555555555555");
	private static final UUID OTHER_DEVICE = UUID.fromString("99999999-2222-4333-8444-555555555555");
	private static final UUID EXISTING = UUID.fromString("0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10");

	/** 2026-11-13T15:30Z, so today's limit resets at 2026-11-14T00:00Z. */
	private static final Instant NOW = Instant.parse("2026-11-13T15:30:00Z");
	private static final Instant DAY_START = Instant.parse("2026-11-13T00:00:00Z");

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13 };
	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F' };
	private static final byte[] GIF = { 'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0 };

	@TestConfiguration
	static class Config {

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		TransactionOperations transactionOperations() {
			return TransactionOperations.withoutTransaction();
		}

		@Bean
		ContributionProperties contributionProperties() {
			return new ContributionProperties(5);
		}

	}

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectMapper objectMapper;

	@MockitoBean
	ContributionRepository repository;

	@MockitoBean
	ImageStore imageStore;

	@MockitoBean
	GenerationDispatcher generationDispatcher;

	@BeforeEach
	void defaults() {
		given(this.repository.findLiveByLink(anyString())).willReturn(Optional.empty());
		given(this.repository.countCreatedSince(any(), any())).willReturn(0);
		given(this.repository.insertPackage(any())).willReturn(Instant.parse("2026-11-13T15:30:00.123456Z"));
	}

	// ------------------------------------------------------------------ success

	@Test
	void createsTextPackageAndReturns202() throws Exception {
		Map<String, Object> data = Map.of("resourceType", "text", "originalTitle", "  Renewable Energy 101  ",
				"author", "Laura Mitchell", "resourceLink", " https://example.org/renewable-energy-101 ", "content",
				"Renewable energy comes from sources that ...");

		String location = post(data).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("processing"))
			.andExpect(jsonPath("$.createdAt").value("2026-11-13T15:30:00.123456Z"))
			.andReturn()
			.getResponse()
			.getHeader("Location");

		NewPackageRow row = insertedRow();
		assertThat(location).isEqualTo("/api/v1/me/packages/" + row.id());
		assertThat(row.deviceId()).isEqualTo(DEVICE);
		assertThat(row.resourceType()).isEqualTo("text");
		assertThat(row.originalTitle()).isEqualTo("Renewable Energy 101");
		assertThat(row.resourceLink()).isEqualTo("https://example.org/renewable-energy-101");
		assertThat(row.sourceContent()).isEqualTo("Renewable energy comes from sources that ...");
		verify(this.imageStore, never()).put(any(), any(), any());

		// Limit is re-checked under the device lock, inside the transaction, before inserting.
		InOrder order = inOrder(this.repository);
		order.verify(this.repository).countCreatedSince(DEVICE, DAY_START);
		order.verify(this.repository).lockDevice(DEVICE);
		order.verify(this.repository).countCreatedSince(DEVICE, DAY_START);
		order.verify(this.repository).insertPackage(any());
	}

	@Test
	void queuesGenerationAfterCommit() throws Exception {
		post(validText()).andExpect(status().isAccepted());

		UUID id = insertedRow().id();
		InOrder order = inOrder(this.repository, this.generationDispatcher);
		order.verify(this.repository).insertPackage(any());
		order.verify(this.generationDispatcher).submit(id);
	}

	@Test
	void returns202EvenWhenGenerationQueueIsFull() throws Exception {
		given(this.generationDispatcher.submit(any())).willReturn(false);

		post(validText()).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("processing"));
		verify(this.imageStore, never()).delete(anyString());
	}

	@Test
	void storesImagesByDetectedTypeWithAltTexts() throws Exception {
		Map<String, Object> data = Map.of("resourceType", "video", "originalTitle", "Solar in 5 minutes", "author",
				"Science Channel", "resourceLink", "https://youtu.be/AbC123", "imageAltTexts",
				List.of("Wind turbines", " "));

		// The second file claims to be PNG but is a JPEG: the bytes decide.
		post(data, image("a.png", "image/png", PNG), image("b.png", "image/png", JPEG))
			.andExpect(status().isAccepted());

		UUID id = insertedRow().id();
		verify(this.imageStore).put("packages/" + id + "/original-1.png", PNG, "image/png");
		verify(this.imageStore).put("packages/" + id + "/original-2.jpg", JPEG, "image/jpeg");

		ArgumentCaptor<NewImageRow> rows = ArgumentCaptor.forClass(NewImageRow.class);
		verify(this.repository, times(2)).insertImage(rows.capture());
		assertThat(rows.getAllValues()).containsExactly(
				new NewImageRow(id, 1, "packages/" + id + "/original-1.png", "image/png", PNG.length, "Wind turbines"),
				new NewImageRow(id, 2, "packages/" + id + "/original-2.jpg", "image/jpeg", JPEG.length, null));
	}

	@Test
	void ignoresEmptyFileParts() throws Exception {
		post(validText(), image("", "application/octet-stream", new byte[0])).andExpect(status().isAccepted());
		verify(this.repository, never()).insertImage(any());
	}

	@Test
	void acceptsDataPartWithoutJsonContentType() throws Exception {
		MockMultipartFile data = new MockMultipartFile("data", "", null, json(validText()));
		this.mvc.perform(multipart("/api/v1/packages").file(data).header(DeviceIdFilter.HEADER, DEVICE))
			.andExpect(status().isAccepted());
	}

	// ---------------------------------------------------------------- validation

	@Test
	void listsEveryInvalidField() throws Exception {
		Map<String, Object> data = Map.of("resourceType", "audio", "originalTitle", "   ", "author", "x".repeat(201),
				"resourceLink", "ftp://example.org/file", "content", "y".repeat(10_001));

		post(data).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.details[*].field").value(
					containsInAnyOrder("resourceType", "originalTitle", "author", "resourceLink", "content")))
			.andExpect(jsonPath("$.error.details[?(@.field == 'originalTitle')].code").value("REQUIRED"))
			.andExpect(jsonPath("$.error.details[?(@.field == 'author')].code").value("TOO_LONG"))
			.andExpect(jsonPath("$.error.details[?(@.field == 'resourceLink')].code").value("INVALID_URL"))
			.andExpect(jsonPath("$.error.details[?(@.field == 'content')].code").value("TOO_LONG"));
		verify(this.repository, never()).findLiveByLink(any());
	}

	@Test
	void videoNeedsYouTubeLink() throws Exception {
		Map<String, Object> data = Map.of("resourceType", "video", "originalTitle", "T", "author", "A",
				"resourceLink", "https://vimeo.com/123");
		post(data).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].field").value("resourceLink"))
			.andExpect(jsonPath("$.error.details[0].code").value("NOT_YOUTUBE"));
	}

	@Test
	void missingOrMalformedDataPart() throws Exception {
		this.mvc.perform(multipart("/api/v1/packages").file(image("a.png", "image/png", PNG))
			.header(DeviceIdFilter.HEADER, DEVICE))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].field").value("data"))
			.andExpect(jsonPath("$.error.details[0].code").value("REQUIRED"));

		MockMultipartFile broken = new MockMultipartFile("data", "", "application/json", "{not json".getBytes());
		this.mvc.perform(multipart("/api/v1/packages").file(broken).header(DeviceIdFilter.HEADER, DEVICE))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].code").value("INVALID_JSON"));
	}

	@Test
	void moreAltTextsThanImages() throws Exception {
		Map<String, Object> data = new java.util.HashMap<>(validText());
		data.put("imageAltTexts", List.of("one", "two"));
		post(data, image("a.png", "image/png", PNG)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].field").value("imageAltTexts"));
	}

	// -------------------------------------------------------------------- images

	@Test
	void tooManyImages() throws Exception {
		post(validText(), image("1.png", "image/png", PNG), image("2.png", "image/png", PNG),
				image("3.png", "image/png", PNG), image("4.png", "image/png", PNG))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("TOO_MANY_IMAGES"));
		verify(this.repository, never()).insertPackage(any());
	}

	@Test
	void imageTooLarge() throws Exception {
		byte[] big = Arrays.copyOf(PNG, 512_001);
		post(validText(), image("big.png", "image/png", big)).andExpect(status().isContentTooLarge())
			.andExpect(jsonPath("$.error.code").value("IMAGE_TOO_LARGE"));
	}

	@Test
	void imageAtLimitIsAccepted() throws Exception {
		post(validText(), image("ok.png", "image/png", Arrays.copyOf(PNG, 512_000))).andExpect(status().isAccepted());
	}

	@Test
	void unsupportedImageType() throws Exception {
		post(validText(), image("anim.png", "image/png", GIF)).andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_IMAGE_TYPE"));
	}

	// ------------------------------------------------------------ duplicate link

	@Test
	void duplicateOfOwnPackageReportsIsOwn() throws Exception {
		given(this.repository.findLiveByLink("https://example.org/a")).willReturn(Optional.of(new LinkOwner(EXISTING, DEVICE)));

		post(validText(), image("a.png", "image/png", PNG)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_LINK"))
			.andExpect(jsonPath("$.error.existingPackageId").value(EXISTING.toString()))
			.andExpect(jsonPath("$.error.isOwn").value(true))
			.andExpect(jsonPath("$.error.requestId").exists());
		verify(this.imageStore, never()).put(any(), any(), any());
		verify(this.repository, never()).insertPackage(any());
	}

	@Test
	void duplicateOfAnotherDevicesPackage() throws Exception {
		given(this.repository.findLiveByLink(any())).willReturn(Optional.of(new LinkOwner(EXISTING, OTHER_DEVICE)));
		post(validText()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.isOwn").value(false));
	}

	@Test
	void duplicateWinsOverDailyLimit() throws Exception {
		// A retry of the device's own request after a timeout must not be reported as a 429.
		given(this.repository.findLiveByLink(any())).willReturn(Optional.of(new LinkOwner(EXISTING, DEVICE)));
		given(this.repository.countCreatedSince(any(), any())).willReturn(5);
		post(validText()).andExpect(status().isConflict());
	}

	@Test
	void raceOnLinkIsReportedAsDuplicateAndCleansUp() throws Exception {
		given(this.repository.findLiveByLink(any())).willReturn(Optional.empty(),
				Optional.of(new LinkOwner(EXISTING, OTHER_DEVICE)));
		given(this.repository.insertPackage(any())).willThrow(new DuplicateKeyException("uq_lp_live_link"));

		post(validText(), image("a.png", "image/png", PNG)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.existingPackageId").value(EXISTING.toString()));

		ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
		verify(this.imageStore).put(path.capture(), any(), any());
		verify(this.imageStore).delete(path.getValue());
	}

	// --------------------------------------------------------------- daily limit

	@Test
	void dailyLimitReached() throws Exception {
		given(this.repository.countCreatedSince(DEVICE, DAY_START)).willReturn(5);

		post(validText(), image("a.png", "image/png", PNG)).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("DAILY_LIMIT_REACHED"))
			.andExpect(jsonPath("$.error.limit").value(5))
			.andExpect(jsonPath("$.error.resetsAt").value("2026-11-14T00:00:00Z"));
		verify(this.imageStore, never()).put(any(), any(), any());
		verify(this.repository, never()).lockDevice(any());
	}

	@Test
	void limitReachedByParallelRequestIsCaughtUnderLock() throws Exception {
		given(this.repository.countCreatedSince(DEVICE, DAY_START)).willReturn(4, 5);

		post(validText(), image("a.png", "image/png", PNG)).andExpect(status().isTooManyRequests());

		verify(this.repository, never()).insertPackage(any());
		ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
		verify(this.imageStore).put(path.capture(), any(), any());
		verify(this.imageStore).delete(path.getValue());
	}

	@Test
	void unexpectedFailureCleansUpImages() throws Exception {
		given(this.repository.insertPackage(any())).willThrow(new IllegalStateException("db down"));

		post(validText(), image("a.png", "image/png", PNG)).andExpect(status().isInternalServerError());
		verify(this.imageStore).delete(anyString());
		verify(this.generationDispatcher, never()).submit(any());
	}

	@Test
	void requiresDeviceId() throws Exception {
		this.mvc.perform(multipart("/api/v1/packages").file(dataPart(validText())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_DEVICE_ID"));
	}

	// ------------------------------------------------------------------- helpers

	private static Map<String, Object> validText() {
		return Map.of("resourceType", "text", "originalTitle", "Title", "author", "Author", "resourceLink",
				"https://example.org/a");
	}

	private ResultActions post(Map<String, Object> data, MockMultipartFile... images) throws Exception {
		var request = multipart("/api/v1/packages").file(dataPart(data));
		for (MockMultipartFile image : images) {
			request.file(image);
		}
		return this.mvc.perform(request.header(DeviceIdFilter.HEADER, DEVICE));
	}

	private MockMultipartFile dataPart(Map<String, Object> data) {
		return new MockMultipartFile("data", "", "application/json", json(data));
	}

	private byte[] json(Map<String, Object> data) {
		return this.objectMapper.writeValueAsBytes(data);
	}

	private static MockMultipartFile image(String name, String contentType, byte[] bytes) {
		return new MockMultipartFile("images", name, contentType, bytes);
	}

	private NewPackageRow insertedRow() {
		ArgumentCaptor<NewPackageRow> row = ArgumentCaptor.forClass(NewPackageRow.class);
		verify(this.repository).insertPackage(row.capture());
		return row.getValue();
	}

}
