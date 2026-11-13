package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One of this device's packages, in any status (API doc, {@code GET /me/packages/{id}}).
 * While {@code processing}, {@code title} and {@code subject} are null and the client shows
 * {@code originalTitle}; when {@code failed}, {@code failureReason} is safe to show.
 */
public record MyPackage(UUID id, String title, String originalTitle, String subject, String resourceType,
		String packageType, String status, String failureReason, int sizeBytes, Instant createdAt,
		Instant publishedAt, boolean isLearnt) {

}
