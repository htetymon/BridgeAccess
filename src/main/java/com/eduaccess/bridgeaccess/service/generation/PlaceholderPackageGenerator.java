package com.eduaccess.bridgeaccess.service.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.repository.GenerationRepository;
import com.eduaccess.bridgeaccess.repository.GenerationRepository.PackageSource;
import com.eduaccess.bridgeaccess.service.GeminiService.LessonSource;
import com.eduaccess.bridgeaccess.service.GeneratedLesson;
import com.eduaccess.bridgeaccess.service.source.SourceTextResolver;

/**
 * Runs the steps built so far: source text (pasted or read from the link), then the Gemini
 * lesson with validation and one retry, then image optimisation. It logs the lesson and fails the
 * package with the generic, retryable message, because saving and publishing are not built
 * yet. Replace with the real generator once they are; it should keep these steps.
 */
@Component
class PlaceholderPackageGenerator implements PackageGenerator {

	private static final Logger log = LoggerFactory.getLogger(PlaceholderPackageGenerator.class);

	private final GenerationRepository repository;

	private final SourceTextResolver sourceTextResolver;

	private final LessonGenerator lessonGenerator;

	private final PackageImageProcessor imageProcessor;

	PlaceholderPackageGenerator(GenerationRepository repository, SourceTextResolver sourceTextResolver,
			LessonGenerator lessonGenerator, PackageImageProcessor imageProcessor) {
		this.repository = repository;
		this.sourceTextResolver = sourceTextResolver;
		this.lessonGenerator = lessonGenerator;
		this.imageProcessor = imageProcessor;
	}

	@Override
	public void generate(Claim claim) {
		PackageSource source = this.repository.findSource(claim.packageId())
			.orElseThrow(() -> new IllegalStateException("Claimed package " + claim.packageId() + " not found"));
		String text = this.sourceTextResolver.resolve(source);
		GeneratedLesson lesson = this.lessonGenerator.generate(claim, new LessonSource(source.originalTitle(),
				source.author(), source.resourceLink(), source.resourceType(), text));
		log.info("Valid lesson for package {}: title='{}', subject={}, {} takeaways, {} concepts, {} questions",
				claim.packageId(), lesson.title(), lesson.subject(), lesson.keyTakeaways().size(),
				lesson.keyConcepts().size(), lesson.quiz().size());
		// After the lesson, so a package that fails validation costs no image work.
		this.imageProcessor.process(claim);
		throw new IllegalStateException("Publishing not built yet");
	}

}
