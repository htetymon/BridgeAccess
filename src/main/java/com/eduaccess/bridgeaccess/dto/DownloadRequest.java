package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;

/** Body of {@code PUT /me/downloads/{packageId}} and payload of {@code download.record}. */
public record DownloadRequest(String status, Long sizeBytes, Instant downloadedAt) {
}
