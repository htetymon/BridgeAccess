package com.eduaccess.bridgeaccess.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Body of {@code POST /sync}: the client's outbox, oldest first (API doc section 9). Each
 * payload is kept as JSON until its type is known, so one bad operation cannot fail the
 * whole batch.
 */
public record SyncRequest(List<Op> ops) {

	/**
	 * @param opId client UUID, echoed in the result to match it to the outbox row; not stored
	 * @param clientTime when the learner did it (informational; payloads carry their own times)
	 */
	public record Op(UUID opId, String type, Instant clientTime, JsonNode payload) {
	}

}
