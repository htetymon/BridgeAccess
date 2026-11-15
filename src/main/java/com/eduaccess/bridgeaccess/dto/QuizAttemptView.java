package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A graded quiz attempt, as stored. {@code isLearnt} is the package's current Learnt state
 * for this device (a passing attempt grants it).
 */
public record QuizAttemptView(UUID id, UUID packageId, int score, int totalQuestions, double percentage,
		int passMarkPercent, boolean passed, Instant attemptedAt, boolean isLearnt, List<Answer> answers) {

	public record Answer(UUID questionId, int selectedIndex, boolean isCorrect) {
	}

}
