package com.eduaccess.bridgeaccess.filter;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

class DeviceIdFilterTest {

	private static final String VALID_DEVICE_ID = "11111111-2222-4333-8444-555555555555";

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
		.addFilters(new DeviceIdFilter(new ObjectMapper()))
		.build();

	@Test
	void rejectsMissingDeviceId() throws Exception {
		mockMvc.perform(get("/api/v1/ping"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string(containsString("INVALID_DEVICE_ID")));
	}

	@Test
	void rejectsMalformedDeviceId() throws Exception {
		mockMvc.perform(get("/api/v1/ping").header(DeviceIdFilter.HEADER, "not-a-uuid"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string(containsString("INVALID_DEVICE_ID")));
	}

	@Test
	void rejectsNonCanonicalUuid() throws Exception {
		mockMvc.perform(get("/api/v1/ping").header(DeviceIdFilter.HEADER, "1-1-1-1-1"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string(containsString("INVALID_DEVICE_ID")));
	}

	@Test
	void acceptsValidDeviceIdAndExposesIt() throws Exception {
		mockMvc.perform(get("/api/v1/ping").header(DeviceIdFilter.HEADER, VALID_DEVICE_ID))
			.andExpect(status().isOk())
			.andExpect(content().string(VALID_DEVICE_ID));
	}

	@Test
	void allowsHealthCheckWithoutDeviceId() throws Exception {
		mockMvc.perform(get("/api/v1/health"))
			.andExpect(status().isOk());
	}

	@RestController
	static class StubController {

		@GetMapping("/api/v1/ping")
		String ping(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId) {
			return deviceId.toString();
		}

		@GetMapping("/api/v1/health")
		String health() {
			return "ok";
		}

	}

}
