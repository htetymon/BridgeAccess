package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code GET /packages}. Metadata only: no images, no long text. */
public record PackageSummary(UUID id, String title, String subject, String resourceType, String packageType,
		int sizeBytes, Instant publishedAt, boolean isDownloaded, boolean isOwn) {
}
