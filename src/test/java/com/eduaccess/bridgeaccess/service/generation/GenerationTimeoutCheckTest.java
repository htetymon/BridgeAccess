package com.eduaccess.bridgeaccess.service.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.eduaccess.bridgeaccess.config.GeminiConfig.GeminiProperties;
import com.eduaccess.bridgeaccess.config.GenerationConfig.GenerationProperties;
import com.eduaccess.bridgeaccess.config.LinkReaderConfig.LinkReaderProperties;

class GenerationTimeoutCheckTest {

	private static final LinkReaderProperties LINK = new LinkReaderProperties(Duration.ofSeconds(5),
			Duration.ofSeconds(5), Duration.ofSeconds(15), 3, DataSize.ofMegabytes(2), 10000, 200, List.of(80, 443),
			"test");

	@Test
	void defaultsLeaveRoomForTheSlowestHealthyJob() {
		Duration worst = GenerationTimeoutCheck.worstCase(generation(Duration.ofMinutes(5)), gemini(Duration.ofSeconds(90)),
				LINK);

		// 15 s link + 2 x (10 s connect + 90 s read) + 3 s pause + 60 s margin
		assertThat(worst).isEqualTo(Duration.ofSeconds(278));
		assertThat(Duration.ofMinutes(5)).isGreaterThan(worst);
	}

	@Test
	void slowGeminiSettingsExceedTheDefaultTimeout() {
		Duration worst = GenerationTimeoutCheck.worstCase(generation(Duration.ofMinutes(5)),
				gemini(Duration.ofSeconds(180)), LINK);

		assertThat(worst).isGreaterThan(Duration.ofMinutes(5));
	}

	private static GenerationProperties generation(Duration timeout) {
		return new GenerationProperties(2, 50, timeout, Duration.ofSeconds(30), Duration.ofSeconds(30),
				Duration.ofSeconds(30), Duration.ofSeconds(3), Duration.ofMinutes(30), Duration.ofHours(1));
	}

	private static GeminiProperties gemini(Duration readTimeout) {
		return new GeminiProperties("key", "model", "https://x", Duration.ofSeconds(10), readTimeout, 8192, 0.3);
	}

}
