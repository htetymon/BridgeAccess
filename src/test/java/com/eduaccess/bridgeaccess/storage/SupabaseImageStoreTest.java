package com.eduaccess.bridgeaccess.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.eduaccess.bridgeaccess.storage.ImageStore.ImageNotFoundException;

class SupabaseImageStoreTest {

	private static final String OBJECT = "https://proj.supabase.co/storage/v1/object/package-images/p1/i1.webp";

	private MockRestServiceServer server;

	private SupabaseImageStore store;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder()
			.baseUrl("https://proj.supabase.co")
			.defaultHeader("apikey", "secret")
			.defaultHeader("Authorization", "Bearer secret");
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.store = new SupabaseImageStore(builder.build(), "package-images");
	}

	@Test
	void uploadsWithUpsertTypeAndLongCache() {
		this.server.expect(requestTo(OBJECT))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("apikey", "secret"))
			.andExpect(header("Authorization", "Bearer secret"))
			.andExpect(header("Content-Type", "image/webp"))
			.andExpect(header("x-upsert", "true"))
			.andExpect(header("cache-control", "max-age=31536000"))
			.andExpect(content().bytes(new byte[] { 1, 2 }))
			.andRespond(withSuccess("{\"Key\":\"package-images/p1/i1.webp\"}", MediaType.APPLICATION_JSON));

		this.store.put("p1/i1.webp", new byte[] { 1, 2 }, "image/webp");

		this.server.verify();
	}

	@Test
	void downloads() {
		this.server.expect(requestTo(OBJECT))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(new byte[] { 7, 8 }, MediaType.parseMediaType("image/webp")));

		assertThat(this.store.get("p1/i1.webp")).containsExactly(7, 8);
	}

	@Test
	void missingObjectIsNotFoundInBothShapes() {
		this.server.expect(requestTo(OBJECT)).andRespond(withStatus(HttpStatus.NOT_FOUND));
		assertThatThrownBy(() -> this.store.get("p1/i1.webp")).isInstanceOf(ImageNotFoundException.class);

		this.server.reset();
		this.server.expect(requestTo(OBJECT))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"statusCode\":\"404\",\"error\":\"not_found\",\"message\":\"Object not found\"}"));
		assertThatThrownBy(() -> this.store.get("p1/i1.webp")).isInstanceOf(ImageNotFoundException.class);
	}

	@Test
	void deleteIgnoresMissingObjects() {
		this.server.expect(requestTo(OBJECT)).andExpect(method(HttpMethod.DELETE)).andRespond(withStatus(HttpStatus.NOT_FOUND));

		this.store.delete("p1/i1.webp");

		this.server.verify();
	}

	@Test
	void otherErrorsFailWithSupabaseMessageButNoKey() {
		this.server.expect(requestTo(OBJECT))
			.andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"error\":\"Unauthorized\",\"message\":\"invalid signature\"}"));

		assertThatThrownBy(() -> this.store.put("p1/i1.webp", new byte[] { 1 }, "image/webp"))
			.isInstanceOf(SupabaseImageStore.StorageException.class)
			.hasMessageContaining("403")
			.hasMessageContaining("invalid signature")
			.hasMessageNotContaining("secret");
	}

	@Test
	void refusesPathTricks() {
		assertThatThrownBy(() -> this.store.get("p1/../other/x.webp")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.store.get("/p1/x.webp")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.store.get("")).isInstanceOf(IllegalArgumentException.class);
	}

}
