package com.eduaccess.bridgeaccess.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ContributionConfig.ContributionProperties.class)
public class ContributionConfig {

	/**
	 * Server-configurable contribution settings.
	 *
	 * @param dailyLimit packages one device may create per UTC day (FR-5.10)
	 * @param maxAiAttempts Gemini calls one package may use across its first run and its retries
	 * ({@code attempt_count}; a run makes 1 or 2). Once used up, retry answers 409 and the
	 * contributor creates the package again, which counts toward the daily limit. Runs that fail
	 * before Gemini (an unreadable link) use none.
	 */
	@ConfigurationProperties("bridgeaccess.contribution")
	public record ContributionProperties(@DefaultValue("5") int dailyLimit, @DefaultValue("6") int maxAiAttempts) {
	}

	/** Injectable time source, so day boundaries can be tested. */
	@Bean
	@ConditionalOnMissingBean
	Clock clock() {
		return Clock.systemUTC();
	}

}
