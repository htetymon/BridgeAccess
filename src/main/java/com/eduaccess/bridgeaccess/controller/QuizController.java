package com.eduaccess.bridgeaccess.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.eduaccess.bridgeaccess.dto.PageResponse;
import com.eduaccess.bridgeaccess.dto.QuizAttemptRequest;
import com.eduaccess.bridgeaccess.dto.QuizAttemptView;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.service.QuizAttemptService;
import com.eduaccess.bridgeaccess.service.QuizAttemptService.Recorded;

/** Quiz attempts (API doc section 7). {@code 201} for a new attempt, {@code 200} for a replay. */
@RestController
@RequestMapping("/me/quiz-attempts")
public class QuizController {

	private final QuizAttemptService service;

	public QuizController(QuizAttemptService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<QuizAttemptView> create(@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@RequestBody QuizAttemptRequest body) {
		Recorded recorded = this.service.record(deviceId, body);
		if (!recorded.created()) {
			return ResponseEntity.ok(recorded.attempt());
		}
		// No Location header: attempts have no single-item endpoint.
		return ResponseEntity.status(HttpStatus.CREATED).body(recorded.attempt());
	}

	@GetMapping
	public ResponseEntity<PageResponse<QuizAttemptView>> list(
			@RequestAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE) UUID deviceId,
			@RequestParam(required = false) UUID packageId, @RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String cursor) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noCache().cachePrivate())
			.body(this.service.list(deviceId, packageId, limit, cursor));
	}

}
