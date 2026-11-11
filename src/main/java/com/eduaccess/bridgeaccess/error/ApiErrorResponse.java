package com.eduaccess.bridgeaccess.error;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ApiErrorResponse(Error error) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Error(String code, String message, List<Detail> details, String requestId) {
	}

	public record Detail(String field, String code, String message) {
	}

}
