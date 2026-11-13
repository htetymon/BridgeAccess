package com.eduaccess.bridgeaccess.service.generation;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.config.GenerationConfig;

/**
 * Hands packages to the bounded generation pool. {@link #submit} never blocks and never
 * throws: if the pool is full or shutting down, the package simply stays waiting in the
 * database and {@code GenerationSweeper} submits it again later.
 */
@Component
public class GenerationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(GenerationDispatcher.class);

	private final ThreadPoolTaskExecutor executor;

	private final GenerationJob job;

	/** Packages queued or running here, so the sweeper does not queue them twice. */
	private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

	public GenerationDispatcher(@Qualifier(GenerationConfig.EXECUTOR) ThreadPoolTaskExecutor executor,
			GenerationJob job) {
		this.executor = executor;
		this.job = job;
	}

	/**
	 * Queues a committed package for generation. Call only after the transaction that wrote
	 * it has committed, or a worker may not see it yet.
	 *
	 * @return whether the package was queued now (false if already queued or the pool is full)
	 */
	public boolean submit(UUID packageId) {
		if (!this.inFlight.add(packageId)) {
			return false;
		}
		try {
			this.executor.execute(() -> {
				try {
					this.job.run(packageId);
				}
				finally {
					this.inFlight.remove(packageId);
				}
			});
			return true;
		}
		catch (TaskRejectedException ex) {
			this.inFlight.remove(packageId);
			log.warn("Generation queue full; package {} will be picked up by the sweeper", packageId);
			return false;
		}
	}

	/** Free queue slots right now, so the sweeper only fetches what can be accepted. */
	public int freeCapacity() {
		int queued = this.executor.getQueueSize();
		int idle = this.executor.getMaxPoolSize() - this.executor.getActiveCount();
		return Math.max(0, this.executor.getQueueCapacity() - queued + Math.max(0, idle));
	}

}
