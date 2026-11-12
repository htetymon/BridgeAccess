package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /packages/{id}/bundle}: everything a device needs to learn offline, including
 * correct answers (grading happens on the device).
 */
public record PackageBundle(int schemaVersion, @JsonProperty("package") Info packageInfo, Content content, Quiz quiz,
		List<Image> images) {

	public static final int SCHEMA_VERSION = 1;

	public record Info(UUID id, String title, String subject, String resourceType, String packageType,
			List<String> steps, Original original, int sizeBytes, Instant publishedAt) {
	}

	public record Original(String title, String author, String link) {
	}

	public record Content(String overview, List<String> keyTakeaways, String summary, List<KeyConcept> keyConcepts) {
	}

	public record KeyConcept(String term, String explanation) {
	}

	public record Quiz(List<Question> questions) {
	}

	public record Question(UUID id, int position, String question, List<String> options, int correctIndex,
			String category, String explanation) {
	}

	public record Image(UUID id, String source, int position, String mimeType, int sizeBytes, String altText,
			String url) {
	}

}
