package com.eduaccess.bridgeaccess.service.generation;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.config.GenerationConfig.GenerationProperties;
import com.eduaccess.bridgeaccess.repository.GenerationRepository;
import com.eduaccess.bridgeaccess.service.GeminiException;
import com.eduaccess.bridgeaccess.service.GeminiService;
import com.eduaccess.bridgeaccess.service.GeminiService.LessonSource;
import com.eduaccess.bridgeaccess.service.GeneratedLesson;
import com.eduaccess.bridgeaccess.service.LessonPrompt;
import com.eduaccess.bridgeaccess.service.generation.LessonValidator.Validation;
import com.eduaccess.bridgeaccess.service.generation.PackageGenerator.Claim;

/**
 * Asks Gemini for a lesson and validates it, trying once more on failure (FR-6.4). If the
 * second attempt fails too, the package fails with the readable "couldn't build a reliable
 * lesson" message (API doc, section 10).
 *
 * <p>
 * What earns the retry:
 * <ul>
 * <li>the lesson failed validation: the retry tells the model what was wrong;</li>
 * <li>the answer was cut off or not the lesson JSON ({@code INVALID_OUTPUT});</li>
 * <li>Gemini was busy or unreachable ({@code UNAVAILABLE}): the retry waits
 * {@code retryDelay} first. If the last attempt is busy too, nothing is wrong with the
 * package: {@link GenerationDeferredException} tells the job to queue it again for later.</li>
 * </ul>
 * Not retried, because a second identical call would fail the same way: a missing or
 * rejected key or unknown model ({@code MISCONFIGURED}, logged as an error for the operator)
 * and content blocked by safety filters ({@code BLOCKED}).
 */
@Component
public class LessonGenerator {

	private static final Logger log = LoggerFactory.getLogger(LessonGenerator.class);

	/** The first call plus one retry. */
	static final int MAX_ATTEMPTS = 2;

	private final GeminiService geminiService;

	private final LessonValidator validator;

	private final GenerationRepository repository;

	private final Duration retryDelay;

	public LessonGenerator(GeminiService geminiService, LessonValidator validator, GenerationRepository repository,
			GenerationProperties properties) {
		this.geminiService = geminiService;
		this.validator = validator;
		this.repository = repository;
		this.retryDelay = properties.retryDelay();
	}

	/**
	 * @return a lesson that passed validation, trimmed and with the canonical subject
	 * @throws GenerationFailedException with a message safe to show the contributor
	 */
	public GeneratedLesson generate(Claim claim, LessonSource source) {
		String correction = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			boolean last = attempt == MAX_ATTEMPTS;
			try {
				GeneratedLesson raw = this.geminiService.generateLesson(source, correction);
				countAttempt(claim);
				Validation validation = this.validator.validate(raw);
				if (validation.isValid()) {
					if (attempt > 1) {
						log.info("Lesson for package {} passed validation on attempt {}", claim.packageId(), attempt);
					}
					return validation.lesson();
				}
				log.warn("Lesson for package {} failed validation on attempt {}/{}: {}", claim.packageId(), attempt,
						MAX_ATTEMPTS, validation.problems());
				correction = LessonPrompt.correction(validation.problems());
			}
			catch (GeminiException ex) {
				switch (ex.kind()) {
					case MISCONFIGURED -> {
						log.error("Gemini is misconfigured; package {} failed without retry: {}", claim.packageId(),
								ex.getMessage());
						throw unreliable(ex);
					}
					case BLOCKED -> {
						countAttempt(claim);
						log.warn("Gemini blocked the lesson for package {}: {}", claim.packageId(), ex.getMessage());
						throw unreliable(ex);
					}
					case UNAVAILABLE -> {
						log.warn("Gemini busy on attempt {}/{} for package {}: {}", attempt, MAX_ATTEMPTS,
								claim.packageId(), ex.getMessage());
						if (last) {
							throw new GenerationDeferredException(ex);
						}
						pause();
						// Keep any correction: the previous problems still need fixing.
					}
					case INVALID_OUTPUT -> {
						countAttempt(claim);
						log.warn("Gemini attempt {}/{} for package {} failed: {}", attempt, MAX_ATTEMPTS,
								claim.packageId(), ex.getMessage());
						if (last) {
							throw unreliable(ex);
						}
						// A malformed answer says nothing useful to correct; ask again plainly.
						correction = null;
					}
				}
			}
		}
		throw new GenerationFailedException(GenerationFailedException.UNRELIABLE_OUTPUT);
	}

	/**
	 * Counts a Gemini call that produced an answer (billed), for the retry budget. Busy or
	 * rejected calls are not counted, so an outage cannot use up a contributor's retries.
	 * Bookkeeping only: a failed write must not cost the lesson.
	 */
	private void countAttempt(Claim claim) {
		try {
			this.repository.recordAttempt(claim.packageId(), claim.claimedAt());
		}
		catch (RuntimeException ex) {
			log.warn("Could not record attempt for package {}", claim.packageId(), ex);
		}
	}

	private void pause() {
		if (this.retryDelay.isZero() || this.retryDelay.isNegative()) {
			return;
		}
		try {
			Thread.sleep(this.retryDelay);
		}
		catch (InterruptedException ex) {
			// Shutting down: hand the package back to the queue for another instance or restart.
			Thread.currentThread().interrupt();
			throw new GenerationDeferredException(ex);
		}
	}

	private static GenerationFailedException unreliable(GeminiException cause) {
		return new GenerationFailedException(GenerationFailedException.UNRELIABLE_OUTPUT, cause);
	}

}
