package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A device's progress in one package (API doc 7, Progress). Steps are sorted. */
public record ProgressView(UUID packageId, List<String> completedSteps, String lastStep, Instant lastOpenedAt,
		boolean isLearnt, Instant learntAt) {
}
