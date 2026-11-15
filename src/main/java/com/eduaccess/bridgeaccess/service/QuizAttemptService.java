package com.eduaccess.bridgeaccess.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eduaccess.bridgeaccess.dto.PageResponse;
import com.eduaccess.bridgeaccess.dto.QuizAttemptRequest;
import com.eduaccess.bridgeaccess.dto.QuizAttemptView;
import com.eduaccess.bridgeaccess.error.ApiErrorResponse.Detail;
import com.eduaccess.bridgeaccess.error.ApiException;
import com.eduaccess.bridgeaccess.error.ErrorCode;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository.AnswerKey;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository.NewAttempt;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository.PageQuery;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository.StoredAttempt;

import tools.jackson.databind.ObjectMapper;

/**
 * Finished quizzes (FR-7.5, FR-7.6, FR-8.3). The server regrades every attempt from the answer
 * key and ignores any score the client worked out. The client-generated attempt ID makes a
 * replay safe: the same ID again returns the stored result instead of a second attempt.
 */
@Service
public class QuizAttemptService {

	public static final int DEFAULT_PASS_MARK_PERCENT = 60;

	/** {@code created} is false when the attempt already existed (a replay). */
	public record Recorded(QuizAttemptView attempt, boolean created) {
	}

	private final QuizAttemptRepository repository;

	private final ObjectMapper objectMapper;

	public QuizAttemptService(QuizAttemptRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	/**
	 * Grades and stores an attempt. A replay of an ID this device already used returns the
	 * stored attempt unchanged, whatever the new body says (the first one wins). An ID used by
	 * another device is a {@code 409}, without revealing anything about that attempt.
	 */
	@Transactional
	public Recorded record(UUID deviceId, QuizAttemptRequest request) {
		List<Detail> errors = new ArrayList<>();
		if (request == null || request.id() == null) {
			errors.add(new Detail("id", "REQUIRED", "This value is required."));
		}
		if (request == null || request.packageId() == null) {
			errors.add(new Detail("packageId", "REQUIRED", "This value is required."));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}

		// Replay first: a stored attempt is returned even if its package was deleted since.
		StoredAttempt existing = this.repository.find(request.id()).orElse(null);
		if (existing != null) {
			return replay(deviceId, existing);
		}

		int passMark = request.passMarkPercent() == null ? DEFAULT_PASS_MARK_PERCENT : request.passMarkPercent();
		if (passMark < 0 || passMark > 100) {
			errors.add(new Detail("passMarkPercent", "OUT_OF_RANGE", "Use a value from 0 to 100."));
		}
		List<AnswerKey> key = this.repository.findAnswerKey(request.packageId()).orElseThrow(ApiException::notFound);
		List<QuizAttemptView.Answer> graded = grade(request.answers(), key, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}

		int score = (int) graded.stream().filter(QuizAttemptView.Answer::isCorrect).count();
		int total = key.size();
		boolean passed = score * 100 >= passMark * total;
		NewAttempt attempt = new NewAttempt(request.id(), deviceId, request.packageId(), score, total, passMark, passed,
				request.attemptedAt() == null ? Instant.now() : request.attemptedAt(), graded);
		boolean created = this.repository.insert(attempt);
		StoredAttempt stored = this.repository.find(request.id()).orElseThrow(IllegalStateException::new);
		if (!created) {
			// The same ID was stored concurrently (a parallel replay): same answer as a replay.
			return replay(deviceId, stored);
		}
		return new Recorded(stored.view(), true);
	}

	public PageResponse<QuizAttemptView> list(UUID deviceId, UUID packageId, Integer limit, String cursor) {
		List<Detail> errors = new ArrayList<>();
		Paging paging = Paging.parse(limit, cursor, this.objectMapper, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		List<QuizAttemptView> rows = this.repository
			.findPage(new PageQuery(deviceId, packageId, paging.afterTime(), paging.afterId(), paging.fetchSize()));
		return paging.page(rows, QuizAttemptView::attemptedAt, QuizAttemptView::id, this.objectMapper);
	}

	private static Recorded replay(UUID deviceId, StoredAttempt stored) {
		if (!stored.deviceId().equals(deviceId)) {
			throw new ApiException(ErrorCode.ATTEMPT_ID_CONFLICT);
		}
		return new Recorded(stored.view(), false);
	}

	/**
	 * Exactly one answer per question of the package (all must be answered, FR-7.5), each
	 * index 0 to 3. Returned in the quiz's question order.
	 */
	static List<QuizAttemptView.Answer> grade(List<QuizAttemptRequest.Answer> answers, List<AnswerKey> key,
			List<Detail> errors) {
		if (key.isEmpty()) {
			errors.add(new Detail("packageId", "NO_QUIZ", "This package has no quiz."));
			return List.of();
		}
		List<QuizAttemptRequest.Answer> sent = answers == null ? List.of() : answers;
		Map<UUID, Integer> selected = new HashMap<>();
		Set<UUID> known = new HashSet<>(key.stream().map(AnswerKey::questionId).toList());
		for (int i = 0; i < sent.size(); i++) {
			QuizAttemptRequest.Answer a = sent.get(i);
			String field = "answers[" + i + "]";
			if (a == null || a.questionId() == null) {
				errors.add(new Detail(field + ".questionId", "REQUIRED", "This value is required."));
			}
			else if (!known.contains(a.questionId())) {
				errors.add(new Detail(field + ".questionId", "UNKNOWN_QUESTION", "This question is not in the quiz."));
			}
			else if (selected.containsKey(a.questionId())) {
				errors.add(new Detail(field + ".questionId", "DUPLICATE_ANSWER", "Answer each question once."));
			}
			else if (a.selectedIndex() == null || a.selectedIndex() < 0 || a.selectedIndex() > 3) {
				errors.add(new Detail(field + ".selectedIndex", "OUT_OF_RANGE", "Use a value from 0 to 3."));
			}
			else {
				selected.put(a.questionId(), a.selectedIndex());
			}
		}
		if (errors.isEmpty() && selected.size() != key.size()) {
			errors.add(new Detail("answers", "INCOMPLETE",
					"Answer all " + key.size() + " questions before submitting."));
		}
		if (!errors.isEmpty()) {
			return List.of();
		}
		return key.stream().map(k -> {
			int choice = selected.get(k.questionId());
			return new QuizAttemptView.Answer(k.questionId(), choice, choice == k.correctIndex());
		}).toList();
	}

}
