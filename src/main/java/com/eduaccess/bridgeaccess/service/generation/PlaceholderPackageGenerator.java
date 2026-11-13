package com.eduaccess.bridgeaccess.service.generation;

import org.springframework.stereotype.Component;

/**
 * Stands in until the Gemini pipeline exists: every package fails with the generic, retryable
 * message, so the job runs end to end (processing to failed) and contributors can retry once
 * the real generator ships. Delete this class when {@code GeminiService} implements
 * {@link PackageGenerator}.
 */
@Component
class PlaceholderPackageGenerator implements PackageGenerator {

	@Override
	public void generate(Claim claim) {
		throw new IllegalStateException("No PackageGenerator implementation yet (Gemini pipeline not wired up)");
	}

}
