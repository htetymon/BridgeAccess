package com.eduaccess.bridgeaccess.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class GenerationDispatcherTest {

	private final GenerationJob job = mock();

	private final CountDownLatch release = new CountDownLatch(1);

	private ThreadPoolTaskExecutor executor;

	private GenerationDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		// One worker, one queue slot.
		this.executor = new ThreadPoolTaskExecutor();
		this.executor.setCorePoolSize(1);
		this.executor.setMaxPoolSize(1);
		this.executor.setQueueCapacity(1);
		this.executor.setWaitForTasksToCompleteOnShutdown(true);
		this.executor.setAwaitTerminationSeconds(5);
		this.executor.initialize();
		this.dispatcher = new GenerationDispatcher(this.executor, this.job);
	}

	@AfterEach
	void tearDown() {
		this.release.countDown();
		this.executor.shutdown();
	}

	@Test
	void runsJobOffTheCallingThread() throws Exception {
		Thread caller = Thread.currentThread();
		CountDownLatch ran = new CountDownLatch(1);
		Thread[] worker = new Thread[1];
		willAnswer(inv -> {
			worker[0] = Thread.currentThread();
			ran.countDown();
			return null;
		}).given(this.job).run(any());

		assertThat(this.dispatcher.submit(UUID.randomUUID())).isTrue();

		assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(worker[0]).isNotSameAs(caller);
	}

	@Test
	void returnsImmediatelyAndRejectsWhenFull() throws Exception {
		blockWorker();
		UUID running = UUID.randomUUID();
		UUID queued = UUID.randomUUID();
		UUID overflow = UUID.randomUUID();

		long start = System.nanoTime();
		assertThat(this.dispatcher.submit(running)).isTrue();
		assertThat(this.dispatcher.submit(queued)).isTrue();
		assertThat(this.dispatcher.submit(overflow)).isFalse();
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));

		// The rejected package can be submitted again once there is room.
		this.release.countDown();
		verify(this.job, timeout(5000)).run(queued);
		assertThat(this.dispatcher.submit(overflow)).isTrue();
		verify(this.job, timeout(5000)).run(overflow);
	}

	@Test
	void doesNotQueueTheSamePackageTwice() {
		blockWorker();
		UUID id = UUID.randomUUID();

		assertThat(this.dispatcher.submit(id)).isTrue();
		assertThat(this.dispatcher.submit(id)).isFalse();
	}

	@Test
	void packageCanBeQueuedAgainAfterItsJobFinished() {
		UUID id = UUID.randomUUID();
		assertThat(this.dispatcher.submit(id)).isTrue();
		verify(this.job, timeout(5000)).run(id);

		// inFlight is cleared in a finally block right after run() returns.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		boolean resubmitted = false;
		while (!resubmitted && System.nanoTime() < deadline) {
			resubmitted = this.dispatcher.submit(id);
			Thread.onSpinWait();
		}
		assertThat(resubmitted).isTrue();
	}

	@Test
	void reportsFreeCapacity() {
		blockWorker();
		assertThat(this.dispatcher.freeCapacity()).isEqualTo(2);
		this.dispatcher.submit(UUID.randomUUID());
		this.dispatcher.submit(UUID.randomUUID());
		assertThat(this.dispatcher.freeCapacity()).isZero();
	}

	private void blockWorker() {
		willAnswer(inv -> {
			this.release.await(10, TimeUnit.SECONDS);
			return null;
		}).given(this.job).run(any());
	}

}
