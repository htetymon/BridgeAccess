package com.eduaccess.bridgeaccess.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

import com.eduaccess.bridgeaccess.config.GenerationConfig.GenerationProperties;
import com.eduaccess.bridgeaccess.repository.GenerationRepository;
import com.eduaccess.bridgeaccess.service.GeminiException;
import com.eduaccess.bridgeaccess.service.GeminiException.Kind;
import com.eduaccess.bridgeaccess.service.GeminiService;
import com.eduaccess.bridgeaccess.service.GeminiService.LessonSource;
import com.eduaccess.bridgeaccess.service.GeneratedLesson;
import com.eduaccess.bridgeaccess.service.generation.PackageGenerator.Claim;

class LessonGeneratorTest {

	private static final Claim CLAIM = new Claim(UUID.fromString("0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10"),
			Instant.parse("2026-11-13T15:30:00Z"));

	private static final LessonSource SOURCE = new LessonSource("Renewable Energy 101", "Laura Mitchell",
			"https://example.org/renewable", "text", "Renewable energy comes from sources that refill naturally.");

	private final GeminiService gemini = mock();

	private final GenerationRepository repository = mock();

	private final LessonGenerator generator = new LessonGenerator(this.gemini, new LessonValidator(), this.repository,
			new GenerationProperties(2, 50, Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(30),
					Duration.ofSeconds(30), Duration.ZERO, Duration.ofMinutes(30), Duration.ofHours(1)));

	@Test
	void returnsAValidLessonFromTheFirstAttempt() {
		given(this.gemini.generateLesson(SOURCE, null)).willReturn(LessonValidatorTest.valid());

		assertThat(this.generator.generate(CLAIM, SOURCE)).isEqualTo(LessonValidatorTest.valid());
		verify(this.gemini, times(1)).generateLesson(any(), any());
		verify(this.repository).recordAttempt(CLAIM.packageId(), CLAIM.claimedAt());
	}

	@Test
	void retriesOnceWithTheProblemsAfterFailedValidation() {
		given(this.gemini.generateLesson(SOURCE, null)).willReturn(badSubject());
		given(this.gemini.generateLesson(eq(SOURCE), argThat(c -> c != null && c.contains("subject \"Cooking\""))))
			.willReturn(LessonValidatorTest.valid());

		assertThat(this.generator.generate(CLAIM, SOURCE).subject()).isEqualTo("Science");
		verify(this.repository, times(2)).recordAttempt(CLAIM.packageId(), CLAIM.claimedAt());
	}

	@Test
	void failsWithReadableReasonWhenTheRetryIsAlsoInvalid() {
		given(this.gemini.generateLesson(eq(SOURCE), any())).willReturn(badSubject());
		given(this.gemini.generateLesson(SOURCE, null)).willReturn(badSubject());

		assertUnreliable();
		verify(this.gemini, times(2)).generateLesson(eq(SOURCE), any());
	}

	@ParameterizedTest
	@EnumSource(names = { "INVALID_OUTPUT", "UNAVAILABLE" })
	void retriesOnceAfterTransientGeminiFailures(Kind kind) {
		given(this.gemini.generateLesson(SOURCE, null)).willThrow(new GeminiException(kind, "first"))
			.willReturn(LessonValidatorTest.valid());

		assertThat(this.generator.generate(CLAIM, SOURCE)).isEqualTo(LessonValidatorTest.valid());
		verify(this.gemini, times(2)).generateLesson(SOURCE, null);
	}

	@Test
	void failsAfterTwoMalformedAnswers() {
		given(this.gemini.generateLesson(SOURCE, null)).willThrow(new GeminiException(Kind.INVALID_OUTPUT, "first"),
				new GeminiException(Kind.INVALID_OUTPUT, "second"));

		assertUnreliable();
		verify(this.gemini, times(2)).generateLesson(SOURCE, null);
		verify(this.repository, times(2)).recordAttempt(CLAIM.packageId(), CLAIM.claimedAt());
	}

	@Test
	void busyTwiceDefersInsteadOfFailingAndUsesNoBudget() {
		given(this.gemini.generateLesson(SOURCE, null)).willThrow(new GeminiException(Kind.UNAVAILABLE, "503"),
				new GeminiException(Kind.UNAVAILABLE, "503"));

		assertThatThrownBy(() -> this.generator.generate(CLAIM, SOURCE))
			.isInstanceOf(GenerationDeferredException.class)
			.hasMessage(GenerationFailedException.UNRELIABLE_OUTPUT);
		verify(this.gemini, times(2)).generateLesson(SOURCE, null);
		verify(this.repository, org.mockito.Mockito.never()).recordAttempt(any(), any());
	}

	@Test
	void invalidLessonThenBusyDefersToo() {
		given(this.gemini.generateLesson(SOURCE, null)).willReturn(badSubject());
		given(this.gemini.generateLesson(eq(SOURCE), argThat(c -> c != null)))
			.willThrow(new GeminiException(Kind.UNAVAILABLE, "503"));

		assertThatThrownBy(() -> this.generator.generate(CLAIM, SOURCE)).isInstanceOf(GenerationDeferredException.class);
		verify(this.repository, times(1)).recordAttempt(CLAIM.packageId(), CLAIM.claimedAt());
	}

	@Test
	void busyThenInvalidLessonKeepsNoStaleCorrection() {
		given(this.gemini.generateLesson(SOURCE, null)).willThrow(new GeminiException(Kind.UNAVAILABLE, "503"))
			.willReturn(LessonValidatorTest.valid());

		assertThat(this.generator.generate(CLAIM, SOURCE)).isEqualTo(LessonValidatorTest.valid());
		verify(this.repository, times(1)).recordAttempt(CLAIM.packageId(), CLAIM.claimedAt());
	}

	@ParameterizedTest
	@EnumSource(names = { "MISCONFIGURED", "BLOCKED" })
	void doesNotRetryWhatWouldFailTheSameWay(Kind kind) {
		given(this.gemini.generateLesson(SOURCE, null)).willThrow(new GeminiException(kind, "no"));

		assertUnreliable();
		verify(this.gemini, times(1)).generateLesson(any(), any());
	}

	@Test
	void invalidLessonThenMalformedAnswerStillFails() {
		given(this.gemini.generateLesson(SOURCE, null)).willReturn(badSubject());
		given(this.gemini.generateLesson(eq(SOURCE), argThat(c -> c != null)))
			.willThrow(new GeminiException(Kind.INVALID_OUTPUT, "cut off"));

		assertUnreliable();
	}

	@Test
	void malformedAnswerIsRetriedWithoutCorrection() {
		given(this.gemini.generateLesson(SOURCE, null)).willThrow(new GeminiException(Kind.INVALID_OUTPUT, "cut off"))
			.willReturn(LessonValidatorTest.valid());

		this.generator.generate(CLAIM, SOURCE);

		verify(this.gemini, times(2)).generateLesson(eq(SOURCE), isNull());
	}

	@Test
	void attemptBookkeepingFailureDoesNotStopGeneration() {
		given(this.repository.recordAttempt(any(), any())).willThrow(new DataAccessResourceFailureException("down"));
		given(this.gemini.generateLesson(SOURCE, null)).willReturn(LessonValidatorTest.valid());

		assertThat(this.generator.generate(CLAIM, SOURCE)).isNotNull();
	}

	private void assertUnreliable() {
		assertThatThrownBy(() -> this.generator.generate(CLAIM, SOURCE)).isInstanceOf(GenerationFailedException.class)
			.hasMessage(GenerationFailedException.UNRELIABLE_OUTPUT);
	}

	private static GeneratedLesson badSubject() {
		GeneratedLesson raw = LessonValidatorTest.valid();
		return new GeneratedLesson(raw.title(), "Cooking", raw.overview(), raw.keyTakeaways(), raw.summary(),
				raw.keyConcepts(), List.copyOf(raw.quiz()));
	}

}
