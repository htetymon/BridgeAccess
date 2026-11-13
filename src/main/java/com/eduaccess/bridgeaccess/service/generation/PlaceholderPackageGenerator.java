package com.eduaccess.bridgeaccess.service.generation;

import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.repository.GenerationRepository;
import com.eduaccess.bridgeaccess.repository.GenerationRepository.PackageSource;
import com.eduaccess.bridgeaccess.service.source.SourceTextResolver;

/**
 * Stands in until the Gemini pipeline exists. It runs the real first step (pasted content or
 * the link, so unreadable links already fail with the right message), then fails with the
 * generic, retryable message because there is no AI step yet. Delete this class when
 * {@code GeminiService} implements {@link PackageGenerator}, keeping the source step.
 */
@Component
class PlaceholderPackageGenerator implements PackageGenerator {

	private final GenerationRepository repository;

	private final SourceTextResolver sourceTextResolver;

	PlaceholderPackageGenerator(GenerationRepository repository, SourceTextResolver sourceTextResolver) {
		this.repository = repository;
		this.sourceTextResolver = sourceTextResolver;
	}

	@Override
	public void generate(Claim claim) {
		PackageSource source = this.repository.findSource(claim.packageId())
			.orElseThrow(() -> new IllegalStateException("Claimed package " + claim.packageId() + " not found"));
		this.sourceTextResolver.resolve(source);
		throw new IllegalStateException("No AI step yet (Gemini pipeline not wired up)");
	}

}
