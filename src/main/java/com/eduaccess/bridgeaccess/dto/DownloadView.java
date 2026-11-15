package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;
import java.util.UUID;

/** This device's download record for one package. */
public record DownloadView(UUID packageId, String status, long sizeBytes, Instant downloadedAt) {
}
