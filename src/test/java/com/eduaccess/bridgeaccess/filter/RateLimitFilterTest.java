package com.eduaccess.bridgeaccess.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eduaccess.bridgeaccess.config.RateLimitProperties;

import tools.jackson.databind.ObjectMapper;

class RateLimitFilterTest {

	private static final String DEVICE = "11111111-2222-4333-8444-555555555555";

	private static final String OTHER = "99999999-2222-4333-8444-555555555555";

	private static final long SECOND = 1_000_000_000L;

	private final AtomicLong now = new AtomicLong(0);

	private RateLimitFilter filter;

	private MockMvc mvc(int perDevice, int perIp, boolean enabled) {
		this.filter = new RateLimitFilter(new RateLimitProperties(enabled, perDevice, perIp), new ObjectMapper(),
				this.now::get);
		return MockMvcBuilders.standaloneSetup(new Stub()).addFilters(this.filter).build();
	}

	private ResultActions call(MockMvc mvc, String device, String ip) throws Exception {
		return mvc.perform(get("/api/v1/ping").header(DeviceIdFilter.HEADER, device).with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}));
	}

	@Test
	void allowsAMinutesAllowanceThenAnswers429WithRetryAfterAndTheErrorEnvelope() throws Exception {
		MockMvc mvc = mvc(3, 100, true);
		for (int i = 0; i < 3; i++) {
			call(mvc, DEVICE, "10.0.0.1").andExpect(status().isOk());
		}
		call(mvc, DEVICE, "10.0.0.1").andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "20")) // 3 per minute: one token every 20 s
			.andExpect(header().exists(RequestIdFilter.HEADER))
			.andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
			.andExpect(jsonPath("$.error.message").isString());
	}

	@Test
	void refillsOverTime() throws Exception {
		MockMvc mvc = mvc(3, 100, true);
		for (int i = 0; i < 3; i++) {
			call(mvc, DEVICE, "10.0.0.1");
		}
		this.now.addAndGet(19 * SECOND);
		call(mvc, DEVICE, "10.0.0.1").andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "1"));
		this.now.addAndGet(1 * SECOND);
		call(mvc, DEVICE, "10.0.0.1").andExpect(status().isOk());
	}

	@Test
	void eachDeviceHasItsOwnAllowanceBehindOneAddress() throws Exception {
		MockMvc mvc = mvc(2, 100, true);
		call(mvc, DEVICE, "10.0.0.1");
		call(mvc, DEVICE, "10.0.0.1");
		call(mvc, DEVICE, "10.0.0.1").andExpect(status().isTooManyRequests());
		call(mvc, OTHER, "10.0.0.1").andExpect(status().isOk()); // a classmate on the same network
	}

	@Test
	void theAddressLimitCapsClientsThatChangeTheirDeviceIdEveryTime() throws Exception {
		MockMvc mvc = mvc(100, 3, true);
		for (int i = 0; i < 3; i++) {
			call(mvc, "%08d-2222-4333-8444-555555555555".formatted(i), "10.0.0.9").andExpect(status().isOk());
		}
		call(mvc, "00000099-2222-4333-8444-555555555555", "10.0.0.9").andExpect(status().isTooManyRequests());
		call(mvc, DEVICE, "10.0.0.10").andExpect(status().isOk()); // another address is unaffected
	}

	@Test
	void requestsWithoutAValidDeviceIdAreLimitedByAddress() throws Exception {
		MockMvc mvc = mvc(100, 2, true);
		call(mvc, "not-a-uuid", "10.0.0.1").andExpect(status().isOk());
		call(mvc, "not-a-uuid", "10.0.0.1").andExpect(status().isOk());
		call(mvc, "not-a-uuid", "10.0.0.1").andExpect(status().isTooManyRequests());
	}

	@Test
	void skipsPreflightRequestsAndCanBeTurnedOff() throws Exception {
		mvc(1, 1, true);
		MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/v1/ping");
		preflight.addHeader("Origin", "https://app.example");
		preflight.addHeader("Access-Control-Request-Method", "GET");
		assertThat(this.filter.shouldNotFilter(preflight)).isTrue();
		assertThat(this.filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
		assertThat(this.filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/ping"))).isFalse();

		MockMvc off = mvc(1, 1, false);
		for (int i = 0; i < 3; i++) {
			call(off, DEVICE, "10.0.0.1").andExpect(status().isOk());
		}
	}

	@Test
	void forgetsIdleClients() throws Exception {
		MockMvc mvc = mvc(10, 100, true);
		call(mvc, DEVICE, "10.0.0.1");
		assertThat(this.filter.bucketCount()).isEqualTo(2); // its device and its address

		this.now.addAndGet(RateLimitFilter.IDLE_NANOS + 61 * SECOND);
		call(mvc, OTHER, "10.0.0.2");
		assertThat(this.filter.bucketCount()).isEqualTo(2); // only the new client's buckets remain
	}

	@RestController
	static class Stub {

		@GetMapping("/api/v1/ping")
		String ping() {
			return "pong";
		}

	}

}
