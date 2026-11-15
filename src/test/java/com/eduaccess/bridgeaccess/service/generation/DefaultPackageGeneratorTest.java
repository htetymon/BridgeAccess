package com.eduaccess.bridgeaccess.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.eduaccess.bridgeaccess.repository.GenerationRepository;
import com.eduaccess.bridgeaccess.repository.GenerationRepository.PackageSource;
import com.eduaccess.bridgeaccess.service.GeminiException;
import com.eduaccess.bridgeaccess.service.GeminiException.Kind;
import com.eduaccess.bridgeaccess.service.GeminiService.LessonSource;
import com.eduaccess.bridgeaccess.service.GeneratedLesson;
import com.eduaccess.bridgeaccess.service.generation.PackageGenerator.Claim;
import com.eduaccess.bridgeaccess.service.source.SourceTextResolver;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class DefaultPackageGeneratorTest {

	private static final UUID ID = UUID.fromString("0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10");

	private static final Instant CLAIMED = Instant.parse("2026-11-13T15:30:00Z");

	private static final String WATCH_URL = "https://www.youtube.com/watch?v=Nmcgbcbpsp8";

	private final GenerationRepository repository = mock();

	private final SourceTextResolver sourceTextResolver = mock();

	private final LessonGenerator lessonGenerator = mock();

	private final PackageImageProcessor imageProcessor = mock();

	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	private final DefaultPackageGenerator generator = new DefaultPackageGenerator(this.repository,
			this.sourceTextResolver, this.lessonGenerator, this.imageProcessor, this.objectMapper);

	@BeforeEach
	void publishes() {
		given(this.repository.findImages(ID)).willReturn(List.of());
		given(this.repository.publish(any(), any(), any(), any(), any(), anyInt())).willReturn(true);
	}

	@Test
	void videoWithoutPastedContentIsWatchedInsteadOfScraped() {
		source(new PackageSource(ID, "video", "Your Words", "Channel", "https://youtu.be/Nmcgbcbpsp8", null));
		given(this.lessonGenerator.generate(any(), any())).willReturn(lesson());

		this.generator.generate(claim());

		verifyNoInteractions(this.sourceTextResolver);
		LessonSource sent = sentSource();
		assertThat(sent.videoUrl()).isEqualTo(WATCH_URL);
		assertThat(sent.sourceText()).isNull();
	}

	@Test
	void pastedTranscriptStillWins() {
		source(new PackageSource(ID, "video", "Your Words", "Channel", "https://youtu.be/Nmcgbcbpsp8",
				"  Pasted transcript  "));
		given(this.sourceTextResolver.resolve(any())).willReturn("Pasted transcript");
		given(this.lessonGenerator.generate(any(), any())).willReturn(lesson());

		this.generator.generate(claim());

		LessonSource sent = sentSource();
		assertThat(sent.videoUrl()).isNull();
		assertThat(sent.sourceText()).isEqualTo("Pasted transcript");
	}

	@Test
	void textLinkIsReadAsBefore() {
		source(new PackageSource(ID, "text", "Article", "Author", "https://example.org/article", null));
		given(this.sourceTextResolver.resolve(any())).willReturn("Article text");
		given(this.lessonGenerator.generate(any(), any())).willReturn(lesson());

		this.generator.generate(claim());

		LessonSource sent = sentSource();
		assertThat(sent.videoUrl()).isNull();
		assertThat(sent.sourceText()).isEqualTo("Article text");
	}

	@Test
	void aVideoGeminiCannotTakeAsksForATranscript() {
		source(new PackageSource(ID, "video", "Your Words", "Channel", "https://youtu.be/Nmcgbcbpsp8", null));
		willThrow(new GenerationFailedException(GenerationFailedException.UNRELIABLE_OUTPUT,
				new GeminiException(Kind.MISCONFIGURED, "400 video too long")))
			.given(this.lessonGenerator)
			.generate(any(), any());

		assertThatThrownBy(() -> this.generator.generate(claim())).isInstanceOfSatisfying(
				GenerationFailedException.class,
				ex -> assertThat(ex.getMessage()).isEqualTo(GenerationFailedException.UNREADABLE_VIDEO));
		verifyNoInteractions(this.imageProcessor);
	}

	@Test
	void aTextFailureKeepsItsGenericMessage() {
		source(new PackageSource(ID, "text", "Article", "Author", "https://example.org/article", null));
		given(this.sourceTextResolver.resolve(any())).willReturn("Article text");
		willThrow(new GenerationFailedException(GenerationFailedException.UNRELIABLE_OUTPUT,
				new GeminiException(Kind.MISCONFIGURED, "bad request")))
			.given(this.lessonGenerator)
			.generate(any(), any());

		assertThatThrownBy(() -> this.generator.generate(claim())).isInstanceOfSatisfying(
				GenerationFailedException.class,
				ex -> assertThat(ex.getMessage()).isEqualTo(GenerationFailedException.UNRELIABLE_OUTPUT));
	}

	private void source(PackageSource source) {
		given(this.repository.findSource(ID)).willReturn(Optional.of(source));
	}

	private LessonSource sentSource() {
		ArgumentCaptor<LessonSource> captor = ArgumentCaptor.forClass(LessonSource.class);
		verify(this.lessonGenerator).generate(any(), captor.capture());
		return captor.getValue();
	}

	private static Claim claim() {
		return new Claim(ID, CLAIMED);
	}

	private static GeneratedLesson lesson() {
		return new GeneratedLesson("How Clean Energy Works", "Science", "Overview", List.of("A", "B", "C"), "Summary",
				List.of(), List.of());
	}

}
