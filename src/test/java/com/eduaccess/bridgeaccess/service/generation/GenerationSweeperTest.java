package com.eduaccess.bridgeaccess.service.generation;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.eduaccess.bridgeaccess.config.GenerationConfig.GenerationProperties;
import com.eduaccess.bridgeaccess.repository.GenerationRepository;

class GenerationSweeperTest {

	private final GenerationRepository repository = mock();

	private final GenerationDispatcher dispatcher = mock();

	private final GenerationProperties properties = new GenerationProperties(2, 50, Duration.ofMinutes(5),
			Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));

	private final GenerationSweeper sweeper = new GenerationSweeper(this.repository, this.dispatcher, this.properties);

	@Test
	void failsTimedOutJobsAndRequeuesWaitingOnes() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		given(this.dispatcher.freeCapacity()).willReturn(10);
		given(this.repository.findWaiting(Duration.ofSeconds(30), 10)).willReturn(List.of(a, b));

		this.sweeper.sweep();

		verify(this.repository).failStuck(Duration.ofMinutes(5), GenerationFailedException.TIMED_OUT);
		verify(this.dispatcher).submit(a);
		verify(this.dispatcher).submit(b);
	}

	@Test
	void doesNotQueryWhenThePoolIsFull() {
		given(this.dispatcher.freeCapacity()).willReturn(0);

		this.sweeper.sweep();

		verify(this.repository, never()).findWaiting(any(), anyInt());
	}

	@Test
	void databaseErrorsDoNotKillTheSchedule() {
		given(this.repository.failStuck(any(), any())).willThrow(new DataAccessResourceFailureException("db down"));

		this.sweeper.sweep(); // does not throw
	}

}
