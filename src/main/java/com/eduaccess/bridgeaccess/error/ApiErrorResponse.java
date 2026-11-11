package com.eduaccess.bridgeaccess.error;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The single error envelope used by every non-2xx response. */
public record ApiErrorResponse(Error error) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Error(String code, String message, List<Detail> details, String requestId) {
	}

	public record Detail(String field, String code, String message) {
	}

	public static ApiErrorResponse of(ErrorCode code, String message, List<Detail> details, String requestId) {
		List<Detail> nonEmptyDetails = (details == null || details.isEmpty()) ? null : details;
		return new ApiErrorResponse(new Error(code.name(), message, nonEmptyDetails, requestId));
	}

	public static ApiErrorResponse of(ErrorCode code, String requestId) {
		return of(code, code.defaultMessage(), null, requestId);
	}

}
