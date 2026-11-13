package com.eduaccess.bridgeaccess.error;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Throw from anywhere in request handling to produce the standard error envelope.
 * {@code GlobalExceptionHandler} turns it into the response.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	private final transient List<ApiErrorResponse.Detail> details;

	/** Code-specific fields added to the error object (API doc, section 8). */
	private final transient Map<String, Object> extras;

	public ApiException(ErrorCode code) {
		this(code, code.defaultMessage(), null);
	}

	public ApiException(ErrorCode code, String message) {
		this(code, message, null);
	}

	public ApiException(ErrorCode code, String message, List<ApiErrorResponse.Detail> details) {
		this(code, message, details, null);
	}

	public ApiException(ErrorCode code, String message, List<ApiErrorResponse.Detail> details,
			Map<String, Object> extras) {
		super(message);
		this.code = code;
		this.details = details;
		this.extras = extras;
	}

	public static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND);
	}

	public static ApiException notFound(String message) {
		return new ApiException(ErrorCode.NOT_FOUND, message);
	}

	public static ApiException validation(List<ApiErrorResponse.Detail> details) {
		return new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), details);
	}

	/** 409: a live package already uses this link. {@code isOwn} lets a client treat its own retry as success. */
	public static ApiException duplicateLink(UUID existingPackageId, boolean isOwn) {
		Map<String, Object> extras = new LinkedHashMap<>();
		extras.put("existingPackageId", existingPackageId);
		extras.put("isOwn", isOwn);
		return new ApiException(ErrorCode.DUPLICATE_LINK, ErrorCode.DUPLICATE_LINK.defaultMessage(), null, extras);
	}

	/** 429: the device used up today's creations (FR-5.10). */
	public static ApiException dailyLimitReached(int limit, Instant resetsAt) {
		Map<String, Object> extras = new LinkedHashMap<>();
		extras.put("limit", limit);
		extras.put("resetsAt", resetsAt);
		return new ApiException(ErrorCode.DAILY_LIMIT_REACHED, ErrorCode.DAILY_LIMIT_REACHED.defaultMessage(), null,
				extras);
	}

	public ErrorCode getCode() {
		return this.code;
	}

	public List<ApiErrorResponse.Detail> getDetails() {
		return this.details;
	}

	public Map<String, Object> getExtras() {
		return this.extras;
	}

}
