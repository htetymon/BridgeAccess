package com.eduaccess.bridgeaccess.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GeminiConfig.GeminiProperties.class)
public class GeminiConfig {

	private static final Logger log = LoggerFactory.getLogger(GeminiConfig.class);

	public static final String REST_CLIENT = "geminiRestClient";

	/**
	 * Gemini API (Google AI Studio key). The key stays on the server (PRD, security).
	 *
	 * @param apiKey from {@code GEMINI_API_KEY}; sent only in the {@code x-goog-api-key}
	 * header, never in a URL or log
	 * @param model model ID, e.g. {@code gemini-3.5-flash-lite}; check the models page before
	 * changing it, older IDs get deprecated
	 * @param baseUrl API root; only changed for tests
	 * @param connectTimeout TCP/TLS connect
	 * @param readTimeout whole response; generation of a lesson usually takes seconds, keep
	 * this well under the job timeout
	 * @param maxOutputTokens upper bound on the answer (and thinking) tokens
	 * @param temperature lower is more faithful to the source; ignored by some newer models
	 */
	@ConfigurationProperties("bridgeaccess.gemini")
	public record GeminiProperties(@DefaultValue("") String apiKey,
			@DefaultValue("gemini-3.5-flash-lite") String model,
			@DefaultValue("https://generativelanguage.googleapis.com") String baseUrl,
			@DefaultValue("10s") Duration connectTimeout, @DefaultValue("90s") Duration readTimeout,
			@DefaultValue("8192") int maxOutputTokens, @DefaultValue("0.3") double temperature) {

		public boolean hasApiKey() {
			return this.apiKey != null && !this.apiKey.isBlank();
		}

		/** Never print the key, even by accident through a log of this record. */
		@Override
		public String toString() {
			return "GeminiProperties[model=" + this.model + ", baseUrl=" + this.baseUrl + ", apiKey="
					+ (hasApiKey() ? "****" : "<missing>") + "]";
		}

	}

	@Bean(name = REST_CLIENT)
	public RestClient geminiRestClient(GeminiProperties properties) {
		if (!properties.hasApiKey()) {
			log.warn("GEMINI_API_KEY is not set: every package will fail at the AI step until it is configured");
		}
		HttpClient http = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(properties.readTimeout());
		return RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
	}

}
