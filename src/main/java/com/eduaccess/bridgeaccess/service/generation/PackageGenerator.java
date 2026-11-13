package com.eduaccess.bridgeaccess.service.generation;

import java.time.Instant;
import java.util.UUID;

/**
 * Turns a claimed package into a published one (API doc, section 8, "What the job does").
 * Called on a generation worker thread, never on a request thread.
 *
 * <p>
 * Implementations must publish with a conditional update ({@code where id = :id and
 * status = 'processing' and processing_started_at = :claimedAt}) so that a run that outlived
 * its timeout cannot overwrite a newer attempt. To fail with a message the contributor
 * should see, throw {@link GenerationFailedException}; any other exception fails the package
 * with a generic message.
 */
public interface PackageGenerator {

	void generate(Claim claim);

	/** A package this worker owns; {@code claimedAt} is the claim token. */
	record Claim(UUID packageId, Instant claimedAt) {
	}

}
