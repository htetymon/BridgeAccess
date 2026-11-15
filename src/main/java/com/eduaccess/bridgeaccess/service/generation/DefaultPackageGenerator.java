package com.eduaccess.bridgeaccess.service.generation;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.repository.GenerationRepository;
import com.eduaccess.bridgeaccess.repository.GenerationRepository.PackageSource;
import com.eduaccess.bridgeaccess.repository.GenerationRepository.StoredImage;
import com.eduaccess.bridgeaccess.service.GeminiService.LessonSource;
import com.eduaccess.bridgeaccess.service.GeneratedLesson;
import com.eduaccess.bridgeaccess.service.source.SourceTextResolver;

import tools.jackson.databind.ObjectMapper;

/**
 * The AI job's steps for one claimed package (FR-6.1 to FR-6.6): source text (pasted or read
 * from the link), the Gemini lesson with validation and one retry, image optimisation, then
 * saving and publishing in one transaction. Failures are thrown as the exceptions
 * {@link GenerationJob} turns into a failed (or deferred) package.
 */
@Component
class DefaultPackageGenerator implements PackageGenerator {

	private static final Logger log = LoggerFactory.getLogger(DefaultPackageGenerator.class);

	/** Package metadata the bundle carries besides the lesson (IDs, original title, URLs...). */
	static final int BUNDLE_OVERHEAD_BYTES = 1_000;

	private final GenerationRepository repository;

	private final SourceTextResolver sourceTextResolver;

	private final LessonGenerator lessonGenerator;

	private final PackageImageProcessor imageProcessor;

	private final ObjectMapper objectMapper;

	DefaultPackageGenerator(GenerationRepository repository, SourceTextResolver sourceTextResolver,
			LessonGenerator lessonGenerator, PackageImageProcessor imageProcessor, ObjectMapper objectMapper) {
		this.repository = repository;
		this.sourceTextResolver = sourceTextResolver;
		this.lessonGenerator = lessonGenerator;
		this.imageProcessor = imageProcessor;
		this.objectMapper = objectMapper;
	}

	@Override
	public void generate(Claim claim) {
		PackageSource source = this.repository.findSource(claim.packageId())
			.orElseThrow(() -> new IllegalStateException("Claimed package " + claim.packageId() + " not found"));
		String text = this.sourceTextResolver.resolve(source);
		GeneratedLesson lesson = this.lessonGenerator.generate(claim, new LessonSource(source.originalTitle(),
				source.author(), source.resourceLink(), source.resourceType(), text));
		// After the lesson, so a package that fails validation costs no image work.
		this.imageProcessor.process(claim);

		String lessonJson = this.objectMapper.writeValueAsString(lesson);
		int size = sizeBytes(lessonJson, this.repository.findImages(claim.packageId()).stream()
			.mapToInt(StoredImage::sizeBytes)
			.sum());
		if (!this.repository.publish(claim.packageId(), claim.claimedAt(), lesson.title(), lesson.subject(),
				lessonJson, size)) {
			// Timed out, retried or deleted meanwhile: whoever owns it now decides its fate.
			log.info("Package {} changed while generating; not published", claim.packageId());
			return;
		}
		log.info("Published package {}: '{}' ({}, {} bytes)", claim.packageId(), lesson.title(), lesson.subject(), size);
	}

	/**
	 * The download size shown to learners (Explore, the large-download warning): the bundle,
	 * estimated from the lesson text plus metadata, and the optimised images.
	 */
	static int sizeBytes(String lessonJson, int imageBytes) {
		return lessonJson.getBytes(StandardCharsets.UTF_8).length + BUNDLE_OVERHEAD_BYTES + imageBytes;
	}

}
