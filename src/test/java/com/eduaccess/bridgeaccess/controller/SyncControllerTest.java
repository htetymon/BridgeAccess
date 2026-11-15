package com.eduaccess.bridgeaccess.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.eduaccess.bridgeaccess.dto.DownloadView;
import com.eduaccess.bridgeaccess.dto.ProgressView;
import com.eduaccess.bridgeaccess.dto.QuizAttemptView;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.repository.BookmarkRepository;
import com.eduaccess.bridgeaccess.repository.BookmarkRepository.AddResult;
import com.eduaccess.bridgeaccess.repository.DownloadRepository;
import com.eduaccess.bridgeaccess.repository.LearningProgressRepository;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository.AnswerKey;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository.StoredAttempt;
import com.eduaccess.bridgeaccess.service.BookmarkService;
import com.eduaccess.bridgeaccess.service.DownloadRecordService;
import com.eduaccess.bridgeaccess.service.ProgressService;
import com.eduaccess.bridgeaccess.service.QuizAttemptService;
import com.eduaccess.bridgeaccess.service.SyncService;

@WebMvcTest(SyncController.class)
@Import({ SyncService.class, ProgressService.class, QuizAttemptService.class, BookmarkService.class,
		DownloadRecordService.class })
class SyncControllerTest {

	private static final UUID DEVICE = UUID.fromString("11111111-2222-4333-8444-555555555555");

	private static final UUID PKG = UUID.fromString("0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10");

	private static final UUID ATTEMPT = UUID.fromString("c3f5b2a1-6d4e-4f08-8a7b-9e1d2c3b4a5f");

	private static final UUID Q1 = UUID.fromString("00000000-0000-4000-8000-000000000001");

	private static final Instant T = Instant.parse("2026-11-10T09:12:00Z");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	LearningProgressRepository progress;

	@MockitoBean
	QuizAttemptRepository quiz;

	@MockitoBean
	BookmarkRepository bookmarks;

	@MockitoBean
	DownloadRepository downloads;

	@BeforeEach
	void stubs() {
		given(this.progress.upsert(any(), any(), anyString(), any(), any()))
			.willReturn(Optional.of(new ProgressView(PKG, List.of("summary"), "key_concepts", T, false, null)));
		given(this.bookmarks.add(any(), any())).willReturn(AddResult.ADDED);
		given(this.downloads.upsert(any(), any(), anyString(), anyInt(), any()))
			.willReturn(Optional.of(new DownloadView(PKG, "completed", 100, T)));
		given(this.quiz.findAnswerKey(PKG)).willReturn(Optional.of(List.of(new AnswerKey(Q1, 2))));
	}

	private static final String PROGRESS = op("11111111-aaaa-4bbb-8ccc-000000000001", "progress.upsert", """
			{"packageId":"%s","completedSteps":["summary"],"lastStep":"key_concepts","lastOpenedAt":"%s"}"""
		.formatted(PKG, T));

	private static final String QUIZ = op("11111111-aaaa-4bbb-8ccc-000000000002", "quiz.attempt.create", """
			{"id":"%s","packageId":"%s","attemptedAt":"%s","passMarkPercent":60,
			 "answers":[{"questionId":"%s","selectedIndex":2}]}""".formatted(ATTEMPT, PKG, T, Q1));

	private static final String BOOKMARK = op("11111111-aaaa-4bbb-8ccc-000000000003", "bookmark.add",
			"{\"packageId\":\"" + PKG + "\"}");

	private static final String DOWNLOAD = op("11111111-aaaa-4bbb-8ccc-000000000004", "download.record",
			"{\"packageId\":\"" + PKG + "\",\"status\":\"completed\",\"sizeBytes\":100,\"downloadedAt\":\"" + T + "\"}");

	private static final String UNBOOKMARK = op("11111111-aaaa-4bbb-8ccc-000000000005", "bookmark.remove",
			"{\"packageId\":\"" + PKG + "\"}");

	private static final String REMOVE = op("11111111-aaaa-4bbb-8ccc-000000000006", "download.remove",
			"{\"packageId\":\"" + PKG + "\"}");

	@Test
	void appliesEveryOperationInOrderAndReportsEachOne() throws Exception {
		given(this.quiz.find(ATTEMPT)).willReturn(Optional.empty(), Optional.of(stored()));
		given(this.quiz.insert(any())).willReturn(true);

		sync(PROGRESS, QUIZ, BOOKMARK, DOWNLOAD, UNBOOKMARK, REMOVE).andExpect(status().isOk())
			.andExpect(jsonPath("$.results.length()").value(6))
			.andExpect(jsonPath("$.results[0].opId").value("11111111-aaaa-4bbb-8ccc-000000000001"))
			.andExpect(jsonPath("$.results[*].status").value(org.hamcrest.Matchers.everyItem(
					org.hamcrest.Matchers.is("ok"))))
			.andExpect(jsonPath("$.results[0].error").doesNotExist());

		InOrder order = inOrder(this.progress, this.quiz, this.bookmarks, this.downloads);
		order.verify(this.progress).upsert(DEVICE, PKG, "{summary}", "key_concepts", T);
		order.verify(this.quiz).insert(any());
		order.verify(this.bookmarks).add(DEVICE, PKG);
		order.verify(this.downloads).upsert(DEVICE, PKG, "completed", 100, T);
		order.verify(this.bookmarks).remove(DEVICE, PKG);
		order.verify(this.downloads).markRemoved(DEVICE, PKG);
	}

	@Test
	void replayingTheSameBatchCreatesNoDuplicateAttempt() throws Exception {
		// First send: the attempt is new. Replay: it is found by its client ID and not inserted again.
		given(this.quiz.find(ATTEMPT)).willReturn(Optional.empty(), Optional.of(stored()), Optional.of(stored()));
		given(this.quiz.insert(any())).willReturn(true);
		given(this.bookmarks.add(DEVICE, PKG)).willReturn(AddResult.ADDED, AddResult.ALREADY_BOOKMARKED);

		for (int i = 0; i < 2; i++) {
			sync(PROGRESS, QUIZ, BOOKMARK, DOWNLOAD).andExpect(status().isOk())
				.andExpect(jsonPath("$.results[*].status").value(org.hamcrest.Matchers.everyItem(
						org.hamcrest.Matchers.is("ok"))));
		}

		verify(this.quiz, times(1)).insert(any());
		// Progress is resent as the same merge (the database unions steps and never removes one).
		verify(this.progress, times(2)).upsert(DEVICE, PKG, "{summary}", "key_concepts", T);
	}

	@Test
	void progressCanOnlyAddSteps() throws Exception {
		// An older event with fewer steps is sent as "add these", never as "set to these":
		// the merge trigger unions it with what is stored, so nothing is un-completed.
		sync(op("11111111-aaaa-4bbb-8ccc-000000000007", "progress.upsert",
				"{\"packageId\":\"" + PKG + "\",\"completedSteps\":[]}"))
			.andExpect(jsonPath("$.results[0].status").value("ok"));
		verify(this.progress).upsert(eq(DEVICE), eq(PKG), eq("{}"), isNull(), isNull());
	}

	@Test
	void aRejectedOperationDoesNotStopTheBatch() throws Exception {
		given(this.bookmarks.add(DEVICE, PKG)).willReturn(AddResult.NOT_AVAILABLE);

		sync(BOOKMARK, DOWNLOAD).andExpect(status().isOk())
			.andExpect(jsonPath("$.results[0].status").value("rejected"))
			.andExpect(jsonPath("$.results[0].error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.results[0].error.message").isString())
			.andExpect(jsonPath("$.results[1].status").value("ok"));
		verify(this.downloads).upsert(DEVICE, PKG, "completed", 100, T);
	}

	@Test
	void badOperationsAreRejectedOneByOne() throws Exception {
		sync(op("11111111-aaaa-4bbb-8ccc-000000000010", "progress.reset", "{\"packageId\":\"" + PKG + "\"}"),
				op("11111111-aaaa-4bbb-8ccc-000000000011", "bookmark.add", "{}"),
				op("11111111-aaaa-4bbb-8ccc-000000000012", "download.record", "{\"packageId\":\"nope\"}"),
				op("11111111-aaaa-4bbb-8ccc-000000000013", "download.record", "null"),
				op("11111111-aaaa-4bbb-8ccc-000000000014", "progress.upsert",
						"{\"packageId\":\"" + PKG + "\",\"completedSteps\":[\"intro\"]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.results[0].error.details[0].code").value("UNKNOWN_TYPE"))
			.andExpect(jsonPath("$.results[1].error.details[0].field").value("packageId"))
			.andExpect(jsonPath("$.results[2].error.details[0].code").value("INVALID_FORMAT"))
			.andExpect(jsonPath("$.results[3].error.details[0].field").value("payload"))
			.andExpect(jsonPath("$.results[4].error.details[0].field").value("completedSteps"))
			.andExpect(jsonPath("$.results[*].error.code").value(org.hamcrest.Matchers.everyItem(
					org.hamcrest.Matchers.is("VALIDATION_FAILED"))));
		verifyNoInteractions(this.progress, this.bookmarks, this.downloads);
	}

	@Test
	void aQuizIdOfAnotherDeviceIsRejectedWithItsCode() throws Exception {
		given(this.quiz.find(ATTEMPT)).willReturn(Optional.of(new StoredAttempt(UUID.randomUUID(), stored().view())));

		sync(QUIZ).andExpect(jsonPath("$.results[0].status").value("rejected"))
			.andExpect(jsonPath("$.results[0].error.code").value("ATTEMPT_ID_CONFLICT"));
	}

	@Test
	void aDatabaseRuleViolationIsRejectedButAnOutageFailsTheRequest() throws Exception {
		given(this.bookmarks.add(DEVICE, PKG)).willThrow(new DataIntegrityViolationException("fk"));
		sync(BOOKMARK).andExpect(status().isOk())
			.andExpect(jsonPath("$.results[0].status").value("rejected"))
			.andExpect(jsonPath("$.results[0].error.code").value("VALIDATION_FAILED"));

		// Might pass on retry: no per-op result, so the client keeps the whole batch and resends it.
		org.mockito.BDDMockito.willThrow(new DataAccessResourceFailureException("down"))
			.given(this.bookmarks)
			.add(DEVICE, PKG);
		sync(BOOKMARK).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
	}

	@Test
	void anEmptyBatchIsFine() throws Exception {
		sync().andExpect(status().isOk()).andExpect(jsonPath("$.results.length()").value(0));
	}

	@Test
	void aMalformedEnvelopeIs400AndAppliesNothing() throws Exception {
		post("{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].field").value("ops"));

		String tooMany = IntStream.range(0, SyncService.MAX_OPS + 1)
			.mapToObj(i -> op(UUID.randomUUID().toString(), "bookmark.add", "{\"packageId\":\"" + PKG + "\"}"))
			.collect(Collectors.joining(","));
		post("{\"ops\":[" + tooMany + "]}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].code").value("TOO_MANY"));

		post("{\"ops\":[" + BOOKMARK + ",{\"type\":\"bookmark.add\",\"payload\":{}}]}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].field").value("ops[1].opId"));

		post("not json").andExpect(status().isBadRequest());
		verifyNoInteractions(this.bookmarks);
	}

	@Test
	void syncNeedsADeviceId() throws Exception {
		this.mvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/sync")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"ops\":[]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_DEVICE_ID"));
	}

	private static StoredAttempt stored() {
		return new StoredAttempt(DEVICE, new QuizAttemptView(ATTEMPT, PKG, 1, 1, 100.0, 60, true, T, true,
				List.of(new QuizAttemptView.Answer(Q1, 2, true))));
	}

	private static String op(String opId, String type, String payload) {
		return "{\"opId\":\"" + opId + "\",\"type\":\"" + type + "\",\"clientTime\":\"" + T + "\",\"payload\":"
				+ payload + "}";
	}

	private ResultActions sync(String... ops) throws Exception {
		return post("{\"ops\":[" + String.join(",", ops) + "]}");
	}

	private ResultActions post(String body) throws Exception {
		return this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/sync")
			.header(DeviceIdFilter.HEADER, DEVICE.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

}
