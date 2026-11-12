package com.eduaccess.bridgeaccess.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

/**
 * Keyset position in the Explore feed, ordered by {@code (published_at desc, id desc)}.
 * Encoded as base64url JSON {@code {"p":"<publishedAt>","i":"<id>"}}. Clients treat it as opaque.
 * {@link Instant#toString()} keeps full (microsecond) precision, so the position round-trips
 * exactly against Postgres {@code timestamptz}.
 */
public record FeedCursor(Instant publishedAt, UUID id) {

	private record Payload(String p, String i) {
	}

	public String encode(ObjectMapper mapper) {
		byte[] json = mapper.writeValueAsBytes(new Payload(publishedAt.toString(), id.toString()));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
	}

	/** Returns {@code null} if the value is not a cursor this server issued. */
	public static FeedCursor decode(String value, ObjectMapper mapper) {
		try {
			byte[] json = Base64.getUrlDecoder().decode(value);
			Payload payload = mapper.readValue(new String(json, StandardCharsets.UTF_8), Payload.class);
			if (payload == null || payload.p() == null || payload.i() == null) {
				return null;
			}
			return new FeedCursor(Instant.parse(payload.p()), UUID.fromString(payload.i()));
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

}
