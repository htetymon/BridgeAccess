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

	/** The HTTP status for {@link Reason#HTTP_STATUS}, otherwise 0. */
	private final int httpStatus;

	public UnreadableLinkException(Reason reason, String detail) {
		this(reason, detail, null);
	}

	public UnreadableLinkException(Reason reason, String detail, Throwable cause) {
		this(reason, 0, detail, cause);
	}

	/** A non-2xx answer from the site. */
	public static UnreadableLinkException httpStatus(int status, String host) {
		return new UnreadableLinkException(Reason.HTTP_STATUS, status, status + " from " + host, null);
	}

	private UnreadableLinkException(Reason reason, int httpStatus, String detail, Throwable cause) {
		super(reason + ": " + detail, cause);
		this.reason = reason;
		this.httpStatus = httpStatus;
	}

	public Reason reason() {
		return this.reason;
	}

	/**
	 * Whether trying again later may work: the network or the site had a problem (timeout,
	 * DNS or connection failure, 429, 5xx), as opposed to a page that cannot be read at all.
	 */
	public boolean isTransient() {
		return switch (this.reason) {
			case TIMEOUT, NETWORK_ERROR -> true;
			case HTTP_STATUS -> this.httpStatus == 408 || this.httpStatus == 429 || this.httpStatus >= 500;
			default -> false;
		};
	}

}
