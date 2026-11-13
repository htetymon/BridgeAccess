package com.eduaccess.bridgeaccess.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration
@EnableConfigurationProperties(LinkReaderConfig.LinkReaderProperties.class)
public class LinkReaderConfig {

	/**
	 * Limits for reading a contributor's link when no content was pasted (FR-6.3).
	 *
	 * @param connectTimeout per TCP/TLS connect attempt
	 * @param readTimeout longest silence while waiting for bytes
	 * @param totalTimeout wall-clock limit for the whole read, redirects included; the
	 * request is cut off when it is reached
	 * @param maxRedirects redirects followed; each target is checked like the original link
	 * @param maxBytes body bytes read (after decompression); the rest is not downloaded
	 * @param maxChars extracted text kept, the same cap as pasted content
	 * @param minChars less readable text than this counts as unreadable (JavaScript-only
	 * pages, paywalls, consent walls)
	 * @param allowedPorts ports a link may name explicitly
	 * @param userAgent sent with every request
	 */
	@ConfigurationProperties("bridgeaccess.link-reader")
	public record LinkReaderProperties(@DefaultValue("5s") Duration connectTimeout,
			@DefaultValue("5s") Duration readTimeout, @DefaultValue("15s") Duration totalTimeout,
			@DefaultValue("3") int maxRedirects, @DefaultValue("2MB") DataSize maxBytes,
			@DefaultValue("10000") int maxChars, @DefaultValue("200") int minChars,
			@DefaultValue({ "80", "443" }) List<Integer> allowedPorts,
			@DefaultValue("BridgeAccessBot/1.0 (+learning package generator)") String userAgent) {
	}

}
