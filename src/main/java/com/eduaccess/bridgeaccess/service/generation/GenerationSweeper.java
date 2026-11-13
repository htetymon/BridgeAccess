package com.eduaccess.bridgeaccess.service.generation;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.config.GenerationConfig.GenerationProperties;
import com.eduaccess.bridgeaccess.repository.GenerationRepository;

/**
 * Periodic safety net for the generation job:
 * <ul>
 * <li>re-queues packages that are still waiting (the pool was full, the instance restarted
 * before a queued job started, or claiming failed);</li>
 * <li>fails packages whose claimed job has run past the timeout, including jobs lost in a
 * crash (the stuck job detector);</li>
 * <li>fails packages that never started within {@code maxWait}, for example because
 * claiming kept failing, so nothing stays in processing forever.</li>
 * </ul>
 * Each step runs on its own: a failure in one is logged and does not skip the others.
 * Safe to run on several instances: claiming and failing are atomic in the database.
 */
@Component
public class GenerationSweeper {

	private static final Logger log = LoggerFactory.getLogger(GenerationSweeper.class);

	private final GenerationRepository repository;

	private final GenerationDispatcher dispatcher;

	private final GenerationProperties properties;

	public GenerationSweeper(GenerationRepository repository, GenerationDispatcher dispatcher,
			GenerationProperties properties) {
		this.repository = repository;
		this.dispatcher = dispatcher;
		this.properties = properties;
	}

	@Scheduled(initialDelayString = "${bridgeaccess.generation.sweep-interval:30s}",
			fixedDelayString = "${bridgeaccess.generation.sweep-interval:30s}")
	public void sweep() {
		step("fail timed-out jobs", this::failStuck);
		step("fail packages that never started", this::failNeverStarted);
		step("re-queue waiting packages", this::requeueWaiting);
	}

	private static void step(String name, Runnable step) {
		try {
			step.run();
		}
		catch (RuntimeException ex) {
			log.warn("Generation sweep could not {}; will try again next interval", name, ex);
		}
	}

	void failStuck() {
		List<UUID> failed = this.repository.failStuck(this.properties.timeout(), GenerationFailedException.TIMED_OUT);
		if (!failed.isEmpty()) {
			log.warn("Marked {} timed-out package(s) failed: {}", failed.size(), failed);
		}
	}

	void failNeverStarted() {
		List<UUID> failed = this.repository.failNeverStarted(this.properties.maxWait(),
				GenerationFailedException.TIMED_OUT);
		if (!failed.isEmpty()) {
			log.warn("Marked {} package(s) that never started within {} failed: {}", failed.size(),
					this.properties.maxWait(), failed);
		}
	}

	void requeueWaiting() {
		int capacity = this.dispatcher.freeCapacity();
		if (capacity == 0) {
			return;
		}
		List<UUID> waiting = this.repository.findWaiting(this.properties.requeueGrace(), capacity);
		int queued = 0;
		for (UUID id : waiting) {
			if (this.dispatcher.submit(id)) {
				queued++;
			}
		}
		if (queued > 0) {
			log.info("Re-queued {} waiting package(s)", queued);
		}
	}

}
