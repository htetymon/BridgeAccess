package com.eduaccess.bridgeaccess.error;

import java.util.List;

/**
 * Throw from anywhere in request handling to produce the standard error envelope.
 * {@code GlobalExceptionHandler} turns it into the response.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	private final transient List<ApiErrorResponse.Detail> details;

	public ApiException(ErrorCode code) {
		this(code, code.defaultMessage(), null);
	}

	public ApiException(ErrorCode code, String message) {
		this(code, message, null);
	}

	public ApiException(ErrorCode code, String message, List<ApiErrorResponse.Detail> details) {
		super(message);
		this.code = code;
		this.details = details;
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

	public ErrorCode getCode() {
		return this.code;
	}

	public List<ApiErrorResponse.Detail> getDetails() {
		return this.details;
	}

}
