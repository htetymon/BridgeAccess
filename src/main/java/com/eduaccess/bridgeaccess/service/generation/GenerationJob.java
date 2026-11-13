package com.eduaccess.bridgeaccess.service.generation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.config.GenerationConfig.GenerationProperties;
import com.eduaccess.bridgeaccess.repository.GenerationRepository;

/**
 * One run of the AI job for one package: claim, generate, then record a failure or, when
 * Gemini was busy, put the package back in the queue.
 */
@Component
public class GenerationJob {

	private static final Logger log = LoggerFactory.getLogger(GenerationJob.class);

	static final String MDC_KEY = "packageId";

	private final GenerationRepository repository;

	private final PackageGenerator generator;

	private final GenerationProperties properties;

	public GenerationJob(GenerationRepository repository, PackageGenerator generator, GenerationProperties properties) {
		this.repository = repository;
		this.generator = generator;
		this.properties = properties;
	}

	/**
	 * Releases the claim so the package is waiting again; the sweeper re-queues it once
	 * {@code requeueGrace} has passed. Only within {@code deferWindow} of its creation, so a
	 * long outage ends in a clear failure instead of a package stuck in processing.
	 */
	private boolean defer(PackageGenerator.Claim claim) {
		try {
			if (this.repository.release(claim.packageId(), claim.claimedAt(), this.properties.deferWindow())) {
				log.info("Gemini busy; package {} back in the queue for a later attempt", claim.packageId());
				return true;
			}
			return false;
		}
		catch (RuntimeException ex) {
			log.warn("Could not put package {} back in the queue", claim.packageId(), ex);
			return false;
		}
	}

	public void run(UUID packageId) {
		MDC.put(MDC_KEY, packageId.toString());
		try {
			Optional<Instant> claimedAt = this.repository.claim(packageId);
			if (claimedAt.isEmpty()) {
				log.debug("Package {} is no longer waiting; skipping", packageId);
				return;
			}
			generate(new PackageGenerator.Claim(packageId, claimedAt.get()));
		}
		catch (RuntimeException ex) {
			// Claiming failed (database down). The package is still waiting; the sweeper retries.
			log.warn("Could not claim package {}", packageId, ex);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	private void generate(PackageGenerator.Claim claim) {
		String reason;
		try {
			this.generator.generate(claim);
			log.info("Generated package {}", claim.packageId());
			return;
		}
		catch (GenerationDeferredException ex) {
			if (defer(claim)) {
				return;
			}
			log.info("Package {} is past the {} defer window; failing it", claim.packageId(),
					this.properties.deferWindow());
			reason = ex.getMessage();
		}
		catch (GenerationFailedException ex) {
			log.info("Generation failed for package {}: {}", claim.packageId(), ex.getMessage(), ex);
			reason = ex.getMessage();
		}
		catch (RuntimeException ex) {
			log.error("Generation crashed for package {}", claim.packageId(), ex);
			reason = GenerationFailedException.UNRELIABLE_OUTPUT;
		}
		try {
			if (!this.repository.markFailed(claim.packageId(), claim.claimedAt(), reason)) {
				log.info("Package {} changed while generating; failure not recorded", claim.packageId());
			}
		}
		catch (RuntimeException ex) {
			// Left claimed in processing: the timeout marks it failed later.
			log.error("Could not mark package {} failed", claim.packageId(), ex);
		}
	}

}
