package com.eduaccess.bridgeaccess.service.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.eduaccess.bridgeaccess.repository.GenerationRepository.PackageSource;
import com.eduaccess.bridgeaccess.service.generation.GenerationFailedException;
import com.eduaccess.bridgeaccess.service.source.UnreadableLinkException.Reason;

class SourceTextResolverTest {

	private static final UUID ID = UUID.randomUUID();

	private final LinkReader linkReader = mock();

	private final SourceTextResolver resolver = new SourceTextResolver(this.linkReader);

	@Test
	void pastedContentWinsAndTheLinkIsNotFetched() {
		String text = this.resolver.resolve(new PackageSource(ID, "text", "https://example.org/a", "  Pasted text  "));

		assertThat(text).isEqualTo("Pasted text");
		verifyNoInteractions(this.linkReader);
	}

	@Test
	void readsTheLinkWhenNothingWasPasted() throws Exception {
		given(this.linkReader.read("https://example.org/a")).willReturn("Page text");

		assertThat(this.resolver.resolve(new PackageSource(ID, "text", "https://example.org/a", "   ")))
			.isEqualTo("Page text");
	}

	@Test
	void unreadableArticleAsksForPastedContent() throws Exception {
		given(this.linkReader.read(any()))
			.willThrow(new UnreadableLinkException(Reason.BLOCKED_ADDRESS, "example.org resolves to 10.0.0.1"));

		assertThatThrownBy(() -> this.resolver.resolve(new PackageSource(ID, "text", "https://example.org/a", null)))
			.isInstanceOf(GenerationFailedException.class)
			.hasMessage("We couldn't read this link. Please paste the content and try again.")
			.hasMessageNotContaining("10.0.0.1");
	}

	@Test
	void unreadableVideoAsksForTheTranscript() throws Exception {
		given(this.linkReader.read(any())).willThrow(new UnreadableLinkException(Reason.TOO_LITTLE_TEXT, "0 chars"));

		assertThatThrownBy(
				() -> this.resolver.resolve(new PackageSource(ID, "video", "https://youtu.be/abc", null)))
			.isInstanceOf(GenerationFailedException.class)
			.hasMessage("We couldn't read this video. Please paste the transcript and try again.");
	}

}
