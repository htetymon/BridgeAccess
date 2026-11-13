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
	 */
	@ConfigurationProperties("bridgeaccess.contribution")
	public record ContributionProperties(@DefaultValue("5") int dailyLimit) {
	}

	/** Injectable time source, so day boundaries can be tested. */
	@Bean
	@ConditionalOnMissingBean
	Clock clock() {
		return Clock.systemUTC();
	}

}
