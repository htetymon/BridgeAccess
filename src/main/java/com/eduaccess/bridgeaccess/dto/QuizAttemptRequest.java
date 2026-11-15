package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /me/quiz-attempts} and payload of {@code quiz.attempt.create}. The
 * client generates {@code id}, which makes a replay safe. There is no score: the server
 * grades the answers itself.
 */
public record QuizAttemptRequest(UUID id, UUID packageId, Instant attemptedAt, Integer passMarkPercent,
		List<Answer> answers) {

	public record Answer(UUID questionId, Integer selectedIndex) {
	}

}
