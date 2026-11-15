package com.eduaccess.bridgeaccess.dto;

import java.util.List;
import java.util.UUID;

import com.eduaccess.bridgeaccess.error.ApiErrorResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One result per operation, in request order. */
public record SyncResponse(List<Result> results) {

	/**
	 * @param status {@code ok} (applied, or a replay that changed nothing) or {@code rejected}
	 * (permanent: the client drops it from the outbox and keeps its local data)
	 * @param error why it was rejected; absent when {@code ok}
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Result(UUID opId, String status, Error error) {

		public static Result ok(UUID opId) {
			return new Result(opId, "ok", null);
		}

		public static Result rejected(UUID opId, Error error) {
			return new Result(opId, "rejected", error);
		}

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Error(String code, String message, List<ApiErrorResponse.Detail> details) {
	}

}
