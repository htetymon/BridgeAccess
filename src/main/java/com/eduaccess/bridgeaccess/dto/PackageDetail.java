package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /packages/{id}}: what the package screen shows before download (FR-7.1). Text
 * for the Learn tab and metadata for Details; no quiz and no full content, so it stays small.
 *
 * @param coverImageUrl the package's first image (lowest position), or {@code null} for a
 * text-only package
 * @param isAiGenerated always {@code true}: drives the AI label (FR-7.9)
 */
public record PackageDetail(UUID id, String title, String overview, List<String> keyTakeaways, String subject,
		String resourceType, String packageType, List<String> steps, PackageBundle.Original original, int sizeBytes,
		int imageCount, String coverImageUrl, Instant publishedAt, boolean isAiGenerated) {
}
