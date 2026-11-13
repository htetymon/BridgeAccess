package com.eduaccess.bridgeaccess.service.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.repository.GenerationRepository.PackageSource;
import com.eduaccess.bridgeaccess.service.generation.GenerationFailedException;

/**
 * Step 1 of the AI job: the text the lesson is built from (FR-6.3). Pasted content wins;
 * otherwise the link is read. If neither gives usable text the job fails and asks the
 * contributor to paste it. There is no fallback to the title, author or the model's own
 * knowledge: the AI never invents content.
 */
@Component
public class SourceTextResolver {

	private static final Logger log = LoggerFactory.getLogger(SourceTextResolver.class);

	private final LinkReader linkReader;

	public SourceTextResolver(LinkReader linkReader) {
		this.linkReader = linkReader;
	}

	/** @throws GenerationFailedException with a contributor-safe message when there is no text */
	public String resolve(PackageSource source) {
		if (source.sourceContent() != null && !source.sourceContent().isBlank()) {
			return source.sourceContent().strip();
		}
		try {
			return this.linkReader.read(source.resourceLink());
		}
		catch (UnreadableLinkException ex) {
			// Details (hosts, addresses) go to the log only, never to the contributor.
			log.info("Could not read link of package {}: {}", source.id(), ex.getMessage());
			throw new GenerationFailedException("video".equals(source.resourceType())
					? GenerationFailedException.UNREADABLE_VIDEO : GenerationFailedException.UNREADABLE_LINK, ex);
		}
	}

}
