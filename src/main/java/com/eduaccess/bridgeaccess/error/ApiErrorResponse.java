package com.eduaccess.bridgeaccess.error;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;

/** The single error envelope used by every non-2xx response. */
public record ApiErrorResponse(Error error) {

	/**
	 * {@code extras} are code-specific fields written alongside the standard ones, for example
	 * {@code existingPackageId} on DUPLICATE_LINK or {@code resetsAt} on DAILY_LIMIT_REACHED.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Error(String code, String message, List<Detail> details, String requestId,
			@JsonAnyGetter Map<String, Object> extras) {
	}

	public record Detail(String field, String code, String message) {
	}

	public static ApiErrorResponse of(ErrorCode code, String message, List<Detail> details, String requestId,
			Map<String, Object> extras) {
		List<Detail> nonEmptyDetails = (details == null || details.isEmpty()) ? null : details;
		Map<String, Object> nonEmptyExtras = (extras == null || extras.isEmpty()) ? null : extras;
		return new ApiErrorResponse(new Error(code.name(), message, nonEmptyDetails, requestId, nonEmptyExtras));
	}

	public static ApiErrorResponse of(ErrorCode code, String message, List<Detail> details, String requestId) {
		return of(code, message, details, requestId, null);
	}

	public static ApiErrorResponse of(ErrorCode code, String requestId) {
		return of(code, code.defaultMessage(), null, requestId);
	}

}
