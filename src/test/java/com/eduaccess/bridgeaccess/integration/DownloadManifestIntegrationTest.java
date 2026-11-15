package com.eduaccess.bridgeaccess.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.JsonNode;

/**
 * Download manifest (FR-4.3, FR-4.4) on a package published by the real job: every part's
 * size and ETag match what its URL actually returns, unchanged parts answer 304, an image
 * resumes with Range, and the download is recorded once.
 */
class DownloadManifestIntegrationTest extends PostgresIntegrationTest {

	@Test
	void theManifestDescribesExactlyWhatEachPartReturns() throws Exception {
		UUID id = publishedPackage();

		MockHttpServletResponse manifestResponse = this.mvc.perform(as(DEVICE, get("/api/v1/packages/" + id + "/manifest")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();
		JsonNode manifest = this.objectMapper.readTree(manifestResponse.getContentAsString());
		JsonNode parts = manifest.get("parts");
		assertThat(parts).hasSize(2);
		assertThat(parts.get(0).get("kind").asString()).isEqualTo("bundle");
		assertThat(parts.get(1).get("kind").asString()).isEqualTo("image");

		long total = 0;
		for (JsonNode part : parts) {
			MockHttpServletResponse response = this.mvc.perform(as(DEVICE, get(part.get("url").asString())))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse();
			assertThat(response.getContentAsByteArray()).hasSize(part.get("sizeBytes").asInt());
			assertThat(response.getHeader("ETag")).isEqualTo(part.get("etag").asString());
			assertThat(response.getContentType()).startsWith(part.get("mimeType").asString());
			total += part.get("sizeBytes").asLong();
		}
		assertThat(manifest.get("totalBytes").asLong()).isEqualTo(total);

		// Unchanged: the manifest and each part revalidate as bodiless 304s.
		this.mvc.perform(as(DEVICE, get("/api/v1/packages/" + id + "/manifest"))
			.header("If-None-Match", manifestResponse.getHeader("ETag"))).andExpect(status().isNotModified());
		for (JsonNode part : parts) {
			this.mvc.perform(as(DEVICE, get(part.get("url").asString())).header("If-None-Match", part.get("etag").asString()))
				.andExpect(status().isNotModified());
		}
	}

	@Test
	void anInterruptedImageResumesWithRange() throws Exception {
		UUID id = publishedPackage();
		JsonNode image = json(as(DEVICE, get("/api/v1/packages/" + id + "/manifest")), 200).get("parts").get(1);
		String url = image.get("url").asString();
		byte[] full = this.mvc.perform(as(DEVICE, get(url))).andReturn().getResponse().getContentAsByteArray();
		int from = full.length / 2;

		MockHttpServletResponse rest = this.mvc.perform(as(DEVICE, get(url)).header("Range", "bytes=" + from + "-"))
			.andExpect(status().isPartialContent())
			.andExpect(header().string("Content-Range", "bytes " + from + "-" + (full.length - 1) + "/" + full.length))
			.andExpect(header().string("ETag", image.get("etag").asString()))
			.andReturn()
			.getResponse();
		assertThat(rest.getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(full, from, full.length));

		this.mvc.perform(as(DEVICE, get(url)).header("Range", "bytes=" + full.length + "-"))
			.andExpect(status().isRequestedRangeNotSatisfiable());
	}

	@Test
	void theCompletedDownloadIsRecordedOnceAndShowsInExplore() throws Exception {
		UUID id = publishedPackage();
		long total = json(as(DEVICE, get("/api/v1/packages/" + id + "/manifest")), 200).get("totalBytes").asLong();
		String body = "{\"status\":\"completed\",\"sizeBytes\":" + total + "}";

		for (int i = 0; i < 2; i++) { // the client may send it again after a lost response
			JsonNode recorded = json(as(DEVICE, put("/api/v1/me/downloads/" + id)).contentType(MediaType.APPLICATION_JSON)
				.content(body), 200);
			assertThat(recorded.get("status").asString()).isEqualTo("completed");
		}
		// A late "started" does not undo it.
		json(as(DEVICE, put("/api/v1/me/downloads/" + id)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"started\",\"sizeBytes\":0}"), 200);

		assertThat(this.jdbc.queryForObject("select count(*) from app.downloads where package_id = ?", Integer.class, id))
			.isEqualTo(1);
		JsonNode downloads = json(as(DEVICE, get("/api/v1/me/downloads")), 200).get("items");
		assertThat(downloads).hasSize(1);
		assertThat(downloads.get(0).get("sizeBytes").asLong()).isEqualTo(total);
		assertThat(json(as(DEVICE, get("/api/v1/packages")), 200).get("items").get(0).get("isDownloaded").asBoolean())
			.isTrue();
	}

	@Test
	void aDeletedPackageCannotBeDownloaded() throws Exception {
		UUID id = publishedPackage();
		this.mvc.perform(as(OTHER_DEVICE, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.delete("/api/v1/me/packages/" + id))).andExpect(status().isNoContent());

		this.mvc.perform(as(DEVICE, get("/api/v1/packages/" + id + "/manifest"))).andExpect(status().isNotFound());
		this.mvc.perform(as(DEVICE, get("/api/v1/packages/" + id + "/bundle"))).andExpect(status().isNotFound());
	}

}
