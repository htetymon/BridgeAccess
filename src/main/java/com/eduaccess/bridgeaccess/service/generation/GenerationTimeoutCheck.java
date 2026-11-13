package com.eduaccess.bridgeaccess.service.generation;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.config.GeminiConfig.GeminiProperties;
import com.eduaccess.bridgeaccess.config.GenerationConfig.GenerationProperties;
import com.eduaccess.bridgeaccess.config.LinkReaderConfig.LinkReaderProperties;

/**
 * Warns at startup when {@code bridgeaccess.generation.timeout} is shorter than the longest a
 * healthy job can take. The stuck job detector would then fail packages whose job is still
 * working, and that job's lesson would be thrown away. The job's own steps are all
 * time-limited, so the worst case can be added up.
 */
@Component
class GenerationTimeoutCheck {

	private static final Logger log = LoggerFactory.getLogger(GenerationTimeoutCheck.class);

	/** Image optimisation, database writes and scheduling slack. */
	static final Duration MARGIN = Duration.ofSeconds(60);

	GenerationTimeoutCheck(GenerationProperties generation, GeminiProperties gemini, LinkReaderProperties linkReader) {
		Duration worstCase = worstCase(generation, gemini, linkReader);
		if (generation.timeout().compareTo(worstCase) < 0) {
			log.warn("bridgeaccess.generation.timeout ({}) is shorter than the longest a healthy job can take (about {}):"
					+ " working jobs may be marked \"took too long\". Raise GENERATION_TIMEOUT or lower the"
					+ " Gemini/link timeouts.", generation.timeout(), worstCase);
		}
	}

	/** Reading the link, two Gemini calls with the pause between them, plus a margin. */
	static Duration worstCase(GenerationProperties generation, GeminiProperties gemini,
			LinkReaderProperties linkReader) {
		Duration geminiCall = gemini.connectTimeout().plus(gemini.readTimeout());
		return linkReader.totalTimeout()
			.plus(geminiCall.multipliedBy(LessonGenerator.MAX_ATTEMPTS))
			.plus(generation.retryDelay())
			.plus(MARGIN);
	}

}
