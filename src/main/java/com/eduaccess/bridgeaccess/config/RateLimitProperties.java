package com.eduaccess.bridgeaccess.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of {@code RateLimitFilter} (API doc section 1, Rate limits). Registered by the
 * filter itself, so every context that has the filter (including web slice tests) has them.
 *
 * @param enabled off only for tests that drive many requests from one device
 * @param perDevicePerMinute requests one device may send per minute
 * @param perIpPerMinute requests one IP address may send per minute (many devices can share
 * one address: a school network, a mobile carrier)
 */
@ConfigurationProperties("bridgeaccess.rate-limit")
public record RateLimitProperties(@DefaultValue("true") boolean enabled, @DefaultValue("120") int perDevicePerMinute,
		@DefaultValue("1200") int perIpPerMinute) {

	public RateLimitProperties {
		if (perDevicePerMinute < 1 || perIpPerMinute < 1) {
			throw new IllegalArgumentException("bridgeaccess.rate-limit limits must be at least 1");
		}
	}

}
