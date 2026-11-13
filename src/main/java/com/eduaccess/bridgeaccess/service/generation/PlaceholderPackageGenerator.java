package com.eduaccess.bridgeaccess.service.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.repository.GenerationRepository;
import com.eduaccess.bridgeaccess.repository.GenerationRepository.PackageSource;
import com.eduaccess.bridgeaccess.service.GeminiService;
import com.eduaccess.bridgeaccess.service.GeminiService.LessonSource;
import com.eduaccess.bridgeaccess.service.GeneratedLesson;
import com.eduaccess.bridgeaccess.service.source.SourceTextResolver;

/**
 * Runs the steps built so far: source text (pasted or read from the link), then the Gemini
 * call. It logs what Gemini returned and then fails the package with the generic, retryable
 * message, because validation (FR-6.4) and saving/publishing are not built yet. Replace
 * with the real generator once they are; it should keep these first two steps.
 */
@Component
class PlaceholderPackageGenerator implements PackageGenerator {

	private static final Logger log = LoggerFactory.getLogger(PlaceholderPackageGenerator.class);

	private final GenerationRepository repository;

	private final SourceTextResolver sourceTextResolver;

	private final GeminiService geminiService;

	PlaceholderPackageGenerator(GenerationRepository repository, SourceTextResolver sourceTextResolver,
			GeminiService geminiService) {
		this.repository = repository;
		this.sourceTextResolver = sourceTextResolver;
		this.geminiService = geminiService;
	}

	@Override
	public void generate(Claim claim) {
		PackageSource source = this.repository.findSource(claim.packageId())
			.orElseThrow(() -> new IllegalStateException("Claimed package " + claim.packageId() + " not found"));
		String text = this.sourceTextResolver.resolve(source);
		GeneratedLesson lesson = this.geminiService.generateLesson(new LessonSource(source.originalTitle(),
				source.author(), source.resourceLink(), source.resourceType(), text));
		log.info("Gemini lesson for package {}: title='{}', subject={}, {} takeaways, {} concepts, {} questions",
				claim.packageId(), lesson.title(), lesson.subject(), size(lesson.keyTakeaways()),
				size(lesson.keyConcepts()), size(lesson.quiz()));
		throw new IllegalStateException("Validation and publishing not built yet");
	}

	private static int size(java.util.List<?> list) {
		return list == null ? 0 : list.size();
	}

}
