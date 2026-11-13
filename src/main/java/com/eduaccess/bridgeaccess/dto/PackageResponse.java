package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;
import java.util.UUID;

/** {@code 202 Accepted} body of {@code POST /packages}: the new package, waiting for the AI job. */
public record PackageResponse(UUID id, String status, Instant createdAt) {

	public static PackageResponse processing(UUID id, Instant createdAt) {
		return new PackageResponse(id, "processing", createdAt);
	}

}
