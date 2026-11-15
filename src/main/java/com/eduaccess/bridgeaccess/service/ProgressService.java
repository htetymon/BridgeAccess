package com.eduaccess.bridgeaccess.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.eduaccess.bridgeaccess.dto.PageResponse;
import com.eduaccess.bridgeaccess.dto.ProgressRequest;
import com.eduaccess.bridgeaccess.dto.ProgressView;
import com.eduaccess.bridgeaccess.error.ApiErrorResponse.Detail;
import com.eduaccess.bridgeaccess.error.ApiException;
import com.eduaccess.bridgeaccess.repository.LearningProgressRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Learning progress (FR-3.4, FR-8.3). Writes merge: steps are unioned and the resume point
 * only moves forward, so the same update can be sent any number of times, in any order.
 * Learnt cannot be set here; only a passing quiz attempt grants it.
 */
@Service
public class ProgressService {

	static final Set<String> STEPS = Set.of("summary", "key_concepts", "diagram", "quiz");

	private final LearningProgressRepository repository;

	private final ObjectMapper objectMapper;

	public ProgressService(LearningProgressRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	public ProgressView upsert(UUID deviceId, UUID packageId, ProgressRequest request) {
		List<Detail> errors = new ArrayList<>();
		TreeSet<String> steps = new TreeSet<>();
		List<String> sent = request == null || request.completedSteps() == null ? List.of() : request.completedSteps();
		for (String step : sent) {
			if (step == null || !STEPS.contains(step)) {
				errors.add(new Detail("completedSteps", "INVALID_VALUE", stepMessage()));
				break;
			}
			steps.add(step);
		}
		String lastStep = request == null ? null : request.lastStep();
		if (lastStep != null && !STEPS.contains(lastStep)) {
			errors.add(new Detail("lastStep", "INVALID_VALUE", stepMessage()));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		// Safe as a literal: only the four fixed step names can reach here.
		String stepsLiteral = "{" + String.join(",", steps) + "}";
		return this.repository
			.upsert(deviceId, packageId, stepsLiteral, lastStep, request == null ? null : request.lastOpenedAt())
			.orElseThrow(ApiException::notFound);
	}

	public ProgressView get(UUID deviceId, UUID packageId) {
		return this.repository.find(deviceId, packageId).orElseThrow(ApiException::notFound);
	}

	public PageResponse<ProgressView> list(UUID deviceId, Integer limit, String cursor) {
		List<Detail> errors = new ArrayList<>();
		Paging paging = Paging.parse(limit, cursor, this.objectMapper, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		List<ProgressView> rows = this.repository.findPage(deviceId, paging.afterTime(), paging.afterId(),
				paging.fetchSize());
		return paging.page(rows, ProgressView::lastOpenedAt, ProgressView::packageId, this.objectMapper);
	}

	private static String stepMessage() {
		return "Use summary, key_concepts, diagram, or quiz.";
	}

}
