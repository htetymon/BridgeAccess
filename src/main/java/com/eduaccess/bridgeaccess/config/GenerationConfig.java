package com.eduaccess.bridgeaccess.config;

import java.time.Duration;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The AI generation job. Packages are generated on a small, bounded pool so that
 * {@code POST /packages} returns at once and a burst of contributions cannot spawn unbounded
 * threads or Gemini calls. Work the pool turns away is picked up later by
 * {@code GenerationSweeper}.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(GenerationConfig.GenerationProperties.class)
public class GenerationConfig {

	public static final String EXECUTOR = "generationExecutor";

	/**
	 * @param workers packages generated at the same time; also caps concurrent Gemini calls
	 * from this instance
	 * @param queueCapacity accepted jobs waiting for a worker; beyond that, jobs wait in the
	 * database until the sweeper re-queues them
	 * @param timeout a claimed job still in {@code processing} after this long is marked
	 * {@code failed}
	 * @param sweepInterval how often the sweeper re-queues waiting packages and fails stuck ones
	 * @param requeueGrace a package must have been waiting this long before the sweeper
	 * re-queues it, so it does not race the request (or instance) that just submitted it
	 * @param shutdownWait how long shutdown waits for running jobs; unfinished ones are failed
	 * by the timeout, unstarted ones are re-queued after restart
	 * @param retryDelay wait before the one retry when Gemini was busy or unreachable (FR-6.4)
	 * @param deferWindow while a package is younger than this, a run that ends with Gemini busy
	 * puts it back in the queue instead of failing it
	 * @param maxWait a package still waiting to start (never claimed) this long after its last
	 * change is marked {@code failed}; covers what the {@code timeout} cannot, since that only
	 * starts once a worker claims the package
	 */
	@ConfigurationProperties("bridgeaccess.generation")
	public record GenerationProperties(@DefaultValue("2") int workers, @DefaultValue("50") int queueCapacity,
			@DefaultValue("5m") Duration timeout, @DefaultValue("30s") Duration sweepInterval,
			@DefaultValue("30s") Duration requeueGrace, @DefaultValue("30s") Duration shutdownWait,
			@DefaultValue("3s") Duration retryDelay, @DefaultValue("30m") Duration deferWindow,
			@DefaultValue("1h") Duration maxWait) {

		public GenerationProperties {
			if (workers < 1) {
				throw new IllegalArgumentException("bridgeaccess.generation.workers must be at least 1");
			}
			if (queueCapacity < 0) {
				throw new IllegalArgumentException("bridgeaccess.generation.queue-capacity must not be negative");
			}
		}

	}

	@Bean(name = EXECUTOR)
	ThreadPoolTaskExecutor generationExecutor(GenerationProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.workers());
		executor.setMaxPoolSize(properties.workers());
		executor.setQueueCapacity(properties.queueCapacity());
		executor.setThreadNamePrefix("generation-");
		// Default AbortPolicy: a full queue throws TaskRejectedException, never runs the job
		// on the request thread.
		executor.setTaskDecorator(copyMdc());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationMillis(properties.shutdownWait().toMillis());
		return executor;
	}

	/** Keeps the submitting request's {@code requestId} in the job's log lines. */
	private static TaskDecorator copyMdc() {
		return task -> {
			Map<String, String> context = MDC.getCopyOfContextMap();
			return () -> {
				Map<String, String> previous = MDC.getCopyOfContextMap();
				if (context != null) {
					MDC.setContextMap(context);
				}
				else {
					MDC.clear();
				}
				try {
					task.run();
				}
				finally {
					if (previous != null) {
						MDC.setContextMap(previous);
					}
					else {
						MDC.clear();
					}
				}
			};
		};
	}

}
