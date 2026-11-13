package com.eduaccess.bridgeaccess.service.generation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.eduaccess.bridgeaccess.config.GenerationConfig.GenerationProperties;
import com.eduaccess.bridgeaccess.repository.GenerationRepository;
import com.eduaccess.bridgeaccess.service.generation.PackageGenerator.Claim;

class GenerationJobTest {

	private static final UUID ID = UUID.fromString("0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10");

	private static final Instant CLAIMED = Instant.parse("2026-11-13T15:30:00.123456Z");

	private final GenerationRepository repository = mock();

	private final PackageGenerator generator = mock();

	private static final Duration WINDOW = Duration.ofMinutes(30);

	private final GenerationJob job = new GenerationJob(this.repository, this.generator,
			new GenerationProperties(2, 50, Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(30),
					Duration.ofSeconds(30), Duration.ZERO, WINDOW));

	@Test
	void generatesClaimedPackage() {
		given(this.repository.claim(ID)).willReturn(Optional.of(CLAIMED));

		this.job.run(ID);

		verify(this.generator).generate(new Claim(ID, CLAIMED));
		verify(this.repository, never()).markFailed(any(), any(), any());
	}

	@Test
	void skipsPackageThatIsNoLongerWaiting() {
		given(this.repository.claim(ID)).willReturn(Optional.empty());

		this.job.run(ID);

		verifyNoInteractions(this.generator);
	}

	@Test
	void recordsSafeMessageOfExpectedFailure() {
		given(this.repository.claim(ID)).willReturn(Optional.of(CLAIMED));
		willThrow(new GenerationFailedException(GenerationFailedException.UNREADABLE_LINK)).given(this.generator)
			.generate(any());

		this.job.run(ID);

		verify(this.repository).markFailed(ID, CLAIMED, GenerationFailedException.UNREADABLE_LINK);
	}

	@Test
	void hidesDetailsOfUnexpectedFailure() {
		given(this.repository.claim(ID)).willReturn(Optional.of(CLAIMED));
		willThrow(new IllegalStateException("NPE in parser at line 42")).given(this.generator).generate(any());

		this.job.run(ID);

		verify(this.repository).markFailed(ID, CLAIMED, GenerationFailedException.UNRELIABLE_OUTPUT);
	}

	@Test
	void claimFailureLeavesPackageWaiting() {
		given(this.repository.claim(ID)).willThrow(new DataAccessResourceFailureException("db down"));

		this.job.run(ID); // does not throw

		verifyNoInteractions(this.generator);
	}

	@Test
	void failureToRecordFailureDoesNotEscape() {
		given(this.repository.claim(ID)).willReturn(Optional.of(CLAIMED));
		willThrow(new IllegalStateException("boom")).given(this.generator).generate(any());
		given(this.repository.markFailed(any(), any(), any()))
			.willThrow(new DataAccessResourceFailureException("db down"));

		this.job.run(ID); // does not throw; the timeout fails it later
	}


	@Test
	void busyGeminiPutsThePackageBackInTheQueue() {
		given(this.repository.claim(ID)).willReturn(Optional.of(CLAIMED));
		willThrow(new GenerationDeferredException(new RuntimeException("503"))).given(this.generator).generate(any());
		given(this.repository.release(ID, CLAIMED, WINDOW)).willReturn(true);

		this.job.run(ID);

		verify(this.repository).release(ID, CLAIMED, WINDOW);
		verify(this.repository, never()).markFailed(any(), any(), any());
	}

	@Test
	void busyGeminiPastTheWindowFailsThePackage() {
		given(this.repository.claim(ID)).willReturn(Optional.of(CLAIMED));
		willThrow(new GenerationDeferredException(new RuntimeException("503"))).given(this.generator).generate(any());
		given(this.repository.release(ID, CLAIMED, WINDOW)).willReturn(false);

		this.job.run(ID);

		verify(this.repository).markFailed(ID, CLAIMED, GenerationFailedException.UNRELIABLE_OUTPUT);
	}

	@Test
	void failingToRequeueFallsBackToFailing() {
		given(this.repository.claim(ID)).willReturn(Optional.of(CLAIMED));
		willThrow(new GenerationDeferredException(new RuntimeException("503"))).given(this.generator).generate(any());
		given(this.repository.release(ID, CLAIMED, WINDOW)).willThrow(new DataAccessResourceFailureException("down"));

		this.job.run(ID);

		verify(this.repository).markFailed(ID, CLAIMED, GenerationFailedException.UNRELIABLE_OUTPUT);
	}

}
