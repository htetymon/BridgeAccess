package com.eduaccess.bridgeaccess.service.generation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.repository.GenerationRepository;

/** One run of the AI job for one package: claim, generate, record failure. */
@Component
public class GenerationJob {

	private static final Logger log = LoggerFactory.getLogger(GenerationJob.class);

	static final String MDC_KEY = "packageId";

	private final GenerationRepository repository;

	private final PackageGenerator generator;

	public GenerationJob(GenerationRepository repository, PackageGenerator generator) {
		this.repository = repository;
		this.generator = generator;
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
