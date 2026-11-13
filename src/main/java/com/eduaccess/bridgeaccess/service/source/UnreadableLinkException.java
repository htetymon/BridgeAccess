package com.eduaccess.bridgeaccess.service.source;

/**
 * The link could not be turned into usable text. The message is for logs only and may
 * contain hosts or addresses; never show it to the contributor.
 */
public class UnreadableLinkException extends Exception {

	public enum Reason {

		INVALID_URL, BLOCKED_ADDRESS, TOO_MANY_REDIRECTS, HTTP_STATUS, UNSUPPORTED_CONTENT_TYPE, TIMEOUT,
		NETWORK_ERROR, TOO_LITTLE_TEXT

	}

	private final Reason reason;

	public UnreadableLinkException(Reason reason, String detail) {
		this(reason, detail, null);
	}

	public UnreadableLinkException(Reason reason, String detail, Throwable cause) {
		super(reason + ": " + detail, cause);
		this.reason = reason;
	}

	public Reason reason() {
		return this.reason;
	}

}
