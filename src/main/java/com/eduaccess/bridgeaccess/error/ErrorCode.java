package com.eduaccess.bridgeaccess.error;

import org.springframework.http.HttpStatus;

/**
 * Error codes from the API contract (section 10), plus a few generic codes for protocol-level
 * failures raised by the framework itself. Each carries its HTTP status and a learner-safe
 * default message.
 */
public enum ErrorCode {

	INVALID_DEVICE_ID(HttpStatus.BAD_REQUEST, "A valid X-Device-Id header is required."),
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Some fields need attention."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "This item could not be found."),
	DUPLICATE_LINK(HttpStatus.CONFLICT, "A package already uses this link."),
	ATTEMPT_ID_CONFLICT(HttpStatus.CONFLICT, "This quiz attempt ID is already in use."),
	NOT_RETRYABLE(HttpStatus.CONFLICT, "Only failed packages can be retried."),
	IMAGE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Images must be 500 KB or smaller."),
	UNSUPPORTED_IMAGE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Images must be PNG, JPG, or WebP."),
	RANGE_NOT_SATISFIABLE(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "The requested range is not available."),
	TOO_MANY_IMAGES(HttpStatus.UNPROCESSABLE_CONTENT, "You can add up to 3 images."),
	DAILY_LIMIT_REACHED(HttpStatus.TOO_MANY_REQUESTS, "You have reached today's limit. Try again tomorrow."),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please wait a moment."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again."),
	SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The service is busy. Please try again shortly."),

	// Generic protocol errors (not in the contract table; raised by the framework).
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "This action is not supported here."),
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "The requested response format is not supported."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request format is not supported."),
	PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The request is too large.");

	private final HttpStatus status;

	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return this.status;
	}

	public String defaultMessage() {
		return this.defaultMessage;
	}

	/** Best-effort mapping for errors that arrive with only an HTTP status. */
	public static ErrorCode fromStatus(int status) {
		return switch (status) {
			case 400 -> VALIDATION_FAILED;
			case 404 -> NOT_FOUND;
			case 405 -> METHOD_NOT_ALLOWED;
			case 406 -> NOT_ACCEPTABLE;
			case 413 -> PAYLOAD_TOO_LARGE;
			case 415 -> UNSUPPORTED_MEDIA_TYPE;
			case 416 -> RANGE_NOT_SATISFIABLE;
			case 429 -> RATE_LIMITED;
			case 503 -> SERVICE_UNAVAILABLE;
			default -> INTERNAL_ERROR;
		};
	}

}
