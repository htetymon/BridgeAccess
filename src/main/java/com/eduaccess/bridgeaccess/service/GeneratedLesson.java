package com.eduaccess.bridgeaccess.service;

import java.util.List;

/**
 * Gemini's lesson, parsed from its JSON exactly as returned (FR-6.1). Field names match the
 * response schema in {@link LessonPrompt}. Not yet checked: the validation step (FR-6.4)
 * decides whether it is good enough to publish. Attribution (original title, author, link)
 * is not part of it on purpose; it stays in the package row and is never rewritten by the AI.
 */
public record GeneratedLesson(String title, String subject, String overview, List<String> keyTakeaways,
		String summary, List<KeyConcept> keyConcepts, List<QuizQuestion> quiz) {

	public record KeyConcept(String term, String explanation) {
	}

	public record QuizQuestion(String question, List<String> options, Integer correctIndex, String explanation) {
	}

}
