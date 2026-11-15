package com.eduaccess.bridgeaccess.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.eduaccess.bridgeaccess.dto.DownloadRequest;
import com.eduaccess.bridgeaccess.dto.ProgressRequest;
import com.eduaccess.bridgeaccess.dto.QuizAttemptRequest;
import com.eduaccess.bridgeaccess.dto.SyncRequest;
import com.eduaccess.bridgeaccess.dto.SyncResponse;
import com.eduaccess.bridgeaccess.dto.SyncResponse.Result;
import com.eduaccess.bridgeaccess.error.ApiErrorResponse.Detail;
import com.eduaccess.bridgeaccess.error.ApiException;
import com.eduaccess.bridgeaccess.error.ErrorCode;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /sync} (FR-8.2, FR-8.3): applies the client's outbox in order, each operation
 * through the same service as its single endpoint, so it follows the same rules.
 *
 * <p>
 * Replaying a batch is safe because every operation is idempotent by a natural key: progress
 * merges (steps are unioned, the resume point only moves forward, in the database trigger),
 * quiz attempts carry the client's attempt ID, bookmarks and downloads have one row per
 * device and package. The {@code opId} is only echoed back, never stored.
 *
 * <p>
 * Each operation stands alone (no batch transaction): one that fails permanently is
 * {@code rejected} and the rest still apply. A failure that may pass (database unavailable,
 * timeout) is not caught, so the whole request fails with a 5xx and the client resends the
 * whole batch, which is safe for the operations already applied.
 */
@Service
public class SyncService {

	private static final Logger log = LoggerFactory.getLogger(SyncService.class);

	public static final int MAX_OPS = 50;

	/** {@code packageId} plus the body of {@code PUT /me/progress/{id}}. */
	record ProgressPayload(UUID packageId, List<String> completedSteps, String lastStep, Instant lastOpenedAt) {
	}

	/** {@code packageId} plus the body of {@code PUT /me/downloads/{id}}. */
	record DownloadPayload(UUID packageId, String status, Long sizeBytes, Instant downloadedAt) {
	}

	record PackagePayload(UUID packageId) {
	}

	private final ProgressService progress;

	private final QuizAttemptService quizAttempts;

	private final BookmarkService bookmarks;

	private final DownloadRecordService downloads;

	private final ObjectMapper objectMapper;

	public SyncService(ProgressService progress, QuizAttemptService quizAttempts, BookmarkService bookmarks,
			DownloadRecordService downloads, ObjectMapper objectMapper) {
		this.progress = progress;
		this.quizAttempts = quizAttempts;
		this.bookmarks = bookmarks;
		this.downloads = downloads;
		this.objectMapper = objectMapper;
	}

	public SyncResponse sync(UUID deviceId, SyncRequest request) {
		List<SyncRequest.Op> ops = validate(request);
		List<Result> results = new ArrayList<>(ops.size());
		for (SyncRequest.Op op : ops) {
			results.add(apply(deviceId, op));
		}
		return new SyncResponse(List.copyOf(results));
	}

	/** {@code 400} only for a malformed envelope; problems inside one operation are per-op results. */
	private static List<SyncRequest.Op> validate(SyncRequest request) {
		if (request == null || request.ops() == null) {
			throw ApiException.validation(List.of(new Detail("ops", "REQUIRED", "This value is required.")));
		}
		List<SyncRequest.Op> ops = request.ops();
		if (ops.size() > MAX_OPS) {
			throw ApiException.validation(List.of(new Detail("ops", "TOO_MANY",
					"Send at most " + MAX_OPS + " operations per request.")));
		}
		List<Detail> errors = new ArrayList<>();
		for (int i = 0; i < ops.size(); i++) {
			if (ops.get(i) == null || ops.get(i).opId() == null) {
				errors.add(new Detail("ops[" + i + "].opId", "REQUIRED", "This value is required."));
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return ops;
	}

	private Result apply(UUID deviceId, SyncRequest.Op op) {
		try {
			switch (op.type() == null ? "" : op.type()) {
				case "progress.upsert" -> {
					ProgressPayload p = payload(op, ProgressPayload.class);
					this.progress.upsert(deviceId, packageId(p.packageId()),
							new ProgressRequest(p.completedSteps(), p.lastStep(), p.lastOpenedAt()));
				}
				case "quiz.attempt.create" -> this.quizAttempts.record(deviceId, payload(op, QuizAttemptRequest.class));
				case "bookmark.add" -> this.bookmarks.add(deviceId, packageId(payload(op, PackagePayload.class).packageId()));
				case "bookmark.remove" ->
					this.bookmarks.remove(deviceId, packageId(payload(op, PackagePayload.class).packageId()));
				case "download.record" -> {
					DownloadPayload p = payload(op, DownloadPayload.class);
					this.downloads.record(deviceId, packageId(p.packageId()),
							new DownloadRequest(p.status(), p.sizeBytes(), p.downloadedAt()));
				}
				case "download.remove" ->
					this.downloads.remove(deviceId, packageId(payload(op, PackagePayload.class).packageId()));
				default -> throw ApiException.validation(List.of(new Detail("type", "UNKNOWN_TYPE",
						"This operation type is not supported.")));
			}
			return Result.ok(op.opId());
		}
		catch (ApiException ex) {
			// 4xx from the operation's own rules: permanent, the client should not resend it.
			if (ex.getCode().status().is5xxServerError()) {
				throw ex;
			}
			log.debug("Sync op {} ({}) rejected: {}", op.opId(), op.type(), ex.getCode());
			return Result.rejected(op.opId(), new SyncResponse.Error(ex.getCode().name(), ex.getMessage(),
					ex.getDetails() == null || ex.getDetails().isEmpty() ? null : ex.getDetails()));
		}
		catch (DataIntegrityViolationException ex) {
			// A database rule refused it; resending the same data would be refused again.
			log.warn("Sync op {} ({}) violated a database rule", op.opId(), op.type(), ex);
			return Result.rejected(op.opId(), new SyncResponse.Error(ErrorCode.VALIDATION_FAILED.name(),
					ErrorCode.VALIDATION_FAILED.defaultMessage(), null));
		}
	}

	private <T> T payload(SyncRequest.Op op, Class<T> type) {
		JsonNode node = op.payload();
		if (node == null || !node.isObject()) {
			throw ApiException.validation(List.of(new Detail("payload", "REQUIRED", "This value is required.")));
		}
		try {
			return this.objectMapper.treeToValue(node, type);
		}
		catch (JacksonException ex) {
			throw ApiException.validation(List.of(new Detail("payload", "INVALID_FORMAT",
					"This value has the wrong format.")));
		}
	}

	private static UUID packageId(UUID packageId) {
		if (packageId == null) {
			throw ApiException.validation(List.of(new Detail("packageId", "REQUIRED", "This value is required.")));
		}
		return packageId;
	}

}
