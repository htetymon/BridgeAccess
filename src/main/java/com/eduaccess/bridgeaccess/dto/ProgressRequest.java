package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;
import java.util.List;

/**
 * Body of {@code PUT /me/progress/{packageId}} and payload of {@code progress.upsert}. Every
 * field is optional: missing steps add nothing, a missing {@code lastStep} keeps the stored
 * resume point, a missing {@code lastOpenedAt} means now.
 */
public record ProgressRequest(List<String> completedSteps, String lastStep, Instant lastOpenedAt) {
}
