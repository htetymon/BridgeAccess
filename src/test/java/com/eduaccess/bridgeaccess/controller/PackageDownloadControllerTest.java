package com.eduaccess.bridgeaccess.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.eduaccess.bridgeaccess.dto.PackageBundle;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.repository.CatalogueRepository;
import com.eduaccess.bridgeaccess.repository.CatalogueRepository.BundleHead;
import com.eduaccess.bridgeaccess.repository.CatalogueRepository.ImageFile;
import com.eduaccess.bridgeaccess.service.PackageDownloadService;
import com.eduaccess.bridgeaccess.service.PackageService;
import com.eduaccess.bridgeaccess.storage.ImageStore;
import com.eduaccess.bridgeaccess.storage.ImageStore.ImageNotFoundException;

@WebMvcTest(PackageController.class)
@Import({ PackageService.class, PackageDownloadService.class })
class PackageDownloadControllerTest {

	private static final UUID DEVICE = UUID.fromString("11111111-2222-4333-8444-555555555555");

	private static final UUID ID = UUID.fromString("0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10");

	private static final UUID IMAGE = UUID.fromString("8e2b7a64-1c50-4f3d-9d6a-0b7e5c3a2f19");

	private static final UUID DIAGRAM = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d");

	private static final byte[] WEBP = new byte[1000];

	@Autowired
	MockMvc mvc;

	@MockitoBean
	CatalogueRepository repository;

	@MockitoBean
	ImageStore imageStore;

	private final ImageFile photo = new ImageFile(IMAGE, "contributor", 1, "image/webp", WEBP.length,
			ID + "/" + IMAGE + ".webp");

	private final ImageFile diagram = new ImageFile(DIAGRAM, "ai_diagram", 0, "image/png", 2500,
			ID + "/" + DIAGRAM + ".png");

	@BeforeEach
	void publishedPackage() {
		for (int i = 0; i < WEBP.length; i++) {
			WEBP[i] = (byte) i;
		}
		PackageBundle.Info info = new PackageBundle.Info(ID, "Renewable Energy Basics", "Science", "text",
				"with_visual", List.of("summary", "key_concepts", "diagram", "quiz"),
				new PackageBundle.Original("Renewable Energy 101", "Laura Mitchell", "https://example.org/re"), 0,
				Instant.parse("2026-11-08T10:15:00Z"));
		PackageBundle.Content content = new PackageBundle.Content("Overview", List.of("a", "b", "c"), "Summary",
				List.of(new PackageBundle.KeyConcept("Solar", "Sunlight")));
		given(repository.findBundleHead(ID)).willReturn(Optional.of(new BundleHead(info, content)));
		given(repository.findQuestions(ID)).willReturn(List.of());
		given(repository.findImages(ID)).willReturn(List.of());
		given(repository.findImageFiles(ID)).willReturn(List.of(diagram, photo));
		given(repository.findImageFile(ID, IMAGE)).willReturn(Optional.of(photo));
		given(imageStore.get(photo.storagePath())).willReturn(WEBP);
	}

	// ---------------------------------------------------------------- manifest

	@Test
	void manifestListsEveryPartWithSizeAndEtag() throws Exception {
		MvcResult bundle = perform(get("/api/v1/packages/" + ID + "/bundle")).andReturn();
		int bundleBytes = bundle.getResponse().getContentAsByteArray().length;
		String bundleEtag = bundle.getResponse().getHeader("ETag");

		perform(get("/api/v1/packages/" + ID + "/manifest")).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-cache, private"))
			.andExpect(header().exists("ETag"))
			.andExpect(jsonPath("$.packageId").value(ID.toString()))
			.andExpect(jsonPath("$.parts", hasSize(3)))
			.andExpect(jsonPath("$.parts[0].kind").value("bundle"))
			.andExpect(jsonPath("$.parts[0].url").value("/api/v1/packages/" + ID + "/bundle"))
			.andExpect(jsonPath("$.parts[0].sizeBytes").value(bundleBytes))
			.andExpect(jsonPath("$.parts[0].etag").value(bundleEtag))
			.andExpect(jsonPath("$.parts[1].kind").value("image"))
			.andExpect(jsonPath("$.parts[1].id").value(DIAGRAM.toString()))
			.andExpect(jsonPath("$.parts[2].url").value("/api/v1/packages/" + ID + "/images/" + IMAGE))
			.andExpect(jsonPath("$.parts[2].mimeType").value("image/webp"))
			.andExpect(jsonPath("$.parts[2].sizeBytes").value(1000))
			.andExpect(jsonPath("$.totalBytes").value(bundleBytes + 2500 + 1000));
	}

	@Test
	void manifestAnswers304WhenUnchanged() throws Exception {
		String etag = perform(get("/api/v1/packages/" + ID + "/manifest")).andReturn().getResponse().getHeader("ETag");

		perform(get("/api/v1/packages/" + ID + "/manifest").header("If-None-Match", etag))
			.andExpect(status().isNotModified())
			.andExpect(header().string("ETag", etag))
			.andExpect(content().bytes(new byte[0]));
	}

	@Test
	void manifestChangesWhenAnImageChanges() throws Exception {
		String before = perform(get("/api/v1/packages/" + ID + "/manifest")).andReturn().getResponse().getHeader("ETag");
		given(repository.findImageFiles(ID)).willReturn(List.of(diagram,
				new ImageFile(IMAGE, "contributor", 1, "image/webp", 900, ID + "/" + IMAGE + "-v2.webp")));

		perform(get("/api/v1/packages/" + ID + "/manifest").header("If-None-Match", before)).andExpect(status().isOk())
			.andExpect(header().string("ETag", org.hamcrest.Matchers.not(before)));
	}

	@Test
	void unpublishedOrDeletedPackageHasNoManifest() throws Exception {
		given(repository.findBundleHead(ID)).willReturn(Optional.empty());

		perform(get("/api/v1/packages/" + ID + "/manifest")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	// ------------------------------------------------------------------ bundle

	@Test
	void bundleHasStrongEtagAnd304() throws Exception {
		MvcResult first = perform(get("/api/v1/packages/" + ID + "/bundle")).andExpect(status().isOk())
			.andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
			.andExpect(jsonPath("$.package.id").value(ID.toString()))
			.andReturn();
		String etag = first.getResponse().getHeader("ETag");
		assertThat(etag).startsWith("\"b-").doesNotStartWith("W/");

		perform(get("/api/v1/packages/" + ID + "/bundle").header("If-None-Match", etag))
			.andExpect(status().isNotModified())
			.andExpect(header().string("ETag", etag));
	}

	// ------------------------------------------------------------------ images

	@Test
	void streamsImageWithEtagAndLongCache() throws Exception {
		perform(get("/api/v1/packages/" + ID + "/images/" + IMAGE)).andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/webp"))
			.andExpect(header().string("Content-Length", "1000"))
			.andExpect(header().string("Accept-Ranges", "bytes"))
			.andExpect(header().string("Cache-Control", "max-age=31536000, private, immutable"))
			.andExpect(header().string("ETag", PackageDownloadService.imageEtag(photo)))
			.andExpect(content().bytes(WEBP));
	}

	@Test
	void resumesWithRange() throws Exception {
		byte[] tail = java.util.Arrays.copyOfRange(WEBP, 600, 1000);

		perform(get("/api/v1/packages/" + ID + "/images/" + IMAGE).header("Range", "bytes=600-"))
			.andExpect(status().isPartialContent())
			.andExpect(header().string("Content-Range", "bytes 600-999/1000"))
			.andExpect(header().string("Content-Length", "400"))
			.andExpect(content().bytes(tail));
	}

	@Test
	void rangePastTheEndIs416() throws Exception {
		perform(get("/api/v1/packages/" + ID + "/images/" + IMAGE).header("Range", "bytes=5000-"))
			.andExpect(status().isRequestedRangeNotSatisfiable())
			.andExpect(header().string("Content-Range", "bytes */1000"));
	}

	@Test
	void imageRevalidationNeverReadsStorage() throws Exception {
		perform(get("/api/v1/packages/" + ID + "/images/" + IMAGE).header("If-None-Match",
				PackageDownloadService.imageEtag(photo)))
			.andExpect(status().isNotModified())
			.andExpect(header().string("Cache-Control", "max-age=31536000, private, immutable"));

		verify(imageStore, never()).get(anyString());
	}

	@Test
	void imageOfAnotherOrHiddenPackageIsNotFound() throws Exception {
		UUID other = UUID.randomUUID();
		given(repository.findImageFile(other, IMAGE)).willReturn(Optional.empty());

		perform(get("/api/v1/packages/" + other + "/images/" + IMAGE)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
		verify(imageStore, never()).get(anyString());
	}

	@Test
	void missingFileIsNotFoundNotAServerError() throws Exception {
		given(imageStore.get(photo.storagePath())).willThrow(new ImageNotFoundException(photo.storagePath()));

		perform(get("/api/v1/packages/" + ID + "/images/" + IMAGE)).andExpect(status().isNotFound());
	}

	private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request)
			throws Exception {
		return mvc.perform(request.header(DeviceIdFilter.HEADER, DEVICE));
	}

}
