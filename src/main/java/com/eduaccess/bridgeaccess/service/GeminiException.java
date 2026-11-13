package com.eduaccess.bridgeaccess.service;

/**
 * A Gemini call did not produce a lesson. Messages are for logs only (never shown to the
 * contributor) and never contain the API key.
 */
public class GeminiException extends RuntimeException {

	public enum Kind {

		/** Rate limit, quota, timeout, network or a 5xx: worth trying again later. */
		UNAVAILABLE,
		/** Missing or rejected API key, unknown model, bad request: fix the configuration. */
		MISCONFIGURED,
		/** Prompt or answer blocked by safety or recitation filters. */
		BLOCKED,
		/** Answer cut off or not valid JSON for the schema: a new attempt may succeed. */
		INVALID_OUTPUT

	}

	private final Kind kind;

	public GeminiException(Kind kind, String message) {
		this(kind, message, null);
	}

	public GeminiException(Kind kind, String message, Throwable cause) {
		super(kind + ": " + message, cause);
		this.kind = kind;
	}

	public Kind kind() {
		return this.kind;
	}

}
