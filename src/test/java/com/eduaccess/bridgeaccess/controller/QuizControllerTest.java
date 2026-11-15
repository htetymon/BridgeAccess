package com.eduaccess.bridgeaccess.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.eduaccess.bridgeaccess.dto.QuizAttemptView;
import com.eduaccess.bridgeaccess.filter.DeviceIdFilter;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository.AnswerKey;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository.NewAttempt;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository.PageQuery;
import com.eduaccess.bridgeaccess.repository.QuizAttemptRepository.StoredAttempt;
import com.eduaccess.bridgeaccess.service.QuizAttemptService;

@WebMvcTest(QuizController.class)
@Import(QuizAttemptService.class)
class QuizControllerTest {

	private static final UUID DEVICE = UUID.fromString("11111111-2222-4333-8444-555555555555");

	private static final UUID OTHER_DEVICE = UUID.fromString("99999999-2222-4333-8444-555555555555");

	private static final UUID PKG = UUID.fromString("0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10");

	private static final UUID ATTEMPT = UUID.fromString("c3f5b2a1-6d4e-4f08-8a7b-9e1d2c3b4a5f");

	private static final Instant T = Instant.parse("2026-11-10T09:20:00Z");

	/** Five questions; the correct answer of question i is i % 4. */
	private static final List<UUID> Q = List.of(UUID.fromString("00000000-0000-4000-8000-000000000001"),
			UUID.fromString("00000000-0000-4000-8000-000000000002"),
			UUID.fromString("00000000-0000-4000-8000-000000000003"),
			UUID.fromString("00000000-0000-4000-8000-000000000004"),
			UUID.fromString("00000000-0000-4000-8000-000000000005"));

	@Autowired
	MockMvc mvc;

	@MockitoBean
	QuizAttemptRepository repository;

	@BeforeEach
	void answerKey() {
		given(this.repository.findAnswerKey(PKG))
			.willReturn(Optional.of(List.of(key(0), key(1), key(2), key(3), key(4))));
	}

	@Test
	void gradesOnTheServerStoresAndReturns201() throws Exception {
		given(this.repository.find(ATTEMPT)).willReturn(Optional.empty(), Optional.of(stored(DEVICE, 4, true)));
		given(this.repository.insert(any())).willReturn(true);

		// 4 of 5 right (the last is wrong); the client's claimed score is ignored.
		as(post("/api/v1/me/quiz-attempts").contentType(MediaType.APPLICATION_JSON)
			.content(body(new int[] { 0, 1, 2, 3, 3 }, 60, "\"score\":5,")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(ATTEMPT.toString()))
			.andExpect(jsonPath("$.score").value(4))
			.andExpect(jsonPath("$.passed").value(true))
			.andExpect(jsonPath("$.isLearnt").value(true));

		ArgumentCaptor<NewAttempt> saved = ArgumentCaptor.forClass(NewAttempt.class);
		verify(this.repository).insert(saved.capture());
		NewAttempt a = saved.getValue();
		assertThat(a.deviceId()).isEqualTo(DEVICE);
		assertThat(a.score()).isEqualTo(4);
		assertThat(a.totalQuestions()).isEqualTo(5);
		assertThat(a.passed()).isTrue();
		assertThat(a.attemptedAt()).isEqualTo(T);
		assertThat(a.answers()).extracting(QuizAttemptView.Answer::isCorrect)
			.containsExactly(true, true, true, true, false);
	}

	@Test
	void passMarkIsInclusiveAndDefaultsTo60() throws Exception {
		given(this.repository.find(ATTEMPT)).willReturn(Optional.empty(), Optional.of(stored(DEVICE, 3, true)));
		given(this.repository.insert(any())).willReturn(true);

		// 3 of 5 = 60 %: a pass at the default mark.
		as(post("/api/v1/me/quiz-attempts").contentType(MediaType.APPLICATION_JSON)
			.content(body(new int[] { 0, 1, 2, 1, 1 }, null, ""))).andExpect(status().isCreated());

		ArgumentCaptor<NewAttempt> saved = ArgumentCaptor.forClass(NewAttempt.class);
		verify(this.repository).insert(saved.capture());
		assertThat(saved.getValue().passMarkPercent()).isEqualTo(60);
		assertThat(saved.getValue().passed()).isTrue();
	}

	@Test
	void belowThePassMarkFails() throws Exception {
		given(this.repository.find(ATTEMPT)).willReturn(Optional.empty(), Optional.of(stored(DEVICE, 3, false)));
		given(this.repository.insert(any())).willReturn(true);

		as(post("/api/v1/me/quiz-attempts").contentType(MediaType.APPLICATION_JSON)
			.content(body(new int[] { 0, 1, 2, 1, 1 }, 80, ""))).andExpect(status().isCreated());

		ArgumentCaptor<NewAttempt> saved = ArgumentCaptor.forClass(NewAttempt.class);
		verify(this.repository).insert(saved.capture());
		assertThat(saved.getValue().passed()).isFalse();
	}

	@Test
	void aReplayReturnsTheStoredAttemptWith200AndWritesNothing() throws Exception {
		given(this.repository.find(ATTEMPT)).willReturn(Optional.of(stored(DEVICE, 4, true)));

		as(post("/api/v1/me/quiz-attempts").contentType(MediaType.APPLICATION_JSON)
			.content(body(new int[] { 1, 1, 1, 1, 1 }, 60, ""))) // a different body: the first one wins
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.score").value(4));

		verify(this.repository, never()).insert(any());
	}

	@Test
	void aConcurrentDuplicateIsAnsweredLikeAReplay() throws Exception {
		given(this.repository.find(ATTEMPT)).willReturn(Optional.empty(), Optional.of(stored(DEVICE, 4, true)));
		given(this.repository.insert(any())).willReturn(false);

		as(post("/api/v1/me/quiz-attempts").contentType(MediaType.APPLICATION_JSON)
			.content(body(new int[] { 0, 1, 2, 3, 3 }, 60, ""))).andExpect(status().isOk());
	}

	@Test
	void anIdOwnedByAnotherDeviceIs409() throws Exception {
		given(this.repository.find(ATTEMPT)).willReturn(Optional.of(stored(OTHER_DEVICE, 4, true)));

		as(post("/api/v1/me/quiz-attempts").contentType(MediaType.APPLICATION_JSON)
			.content(body(new int[] { 0, 1, 2, 3, 3 }, 60, "")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ATTEMPT_ID_CONFLICT"))
			.andExpect(jsonPath("$.score").doesNotExist());
	}

	@Test
	void everyQuestionMustBeAnsweredOnce() throws Exception {
		given(this.repository.find(ATTEMPT)).willReturn(Optional.empty());

		as(post("/api/v1/me/quiz-attempts").contentType(MediaType.APPLICATION_JSON)
			.content(body(new int[] { 0, 1, 2, 3 }, 60, "")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].code").value("INCOMPLETE"));

		String duplicate = """
				{"id":"%s","packageId":"%s","answers":[
				 {"questionId":"%s","selectedIndex":0},{"questionId":"%s","selectedIndex":1},
				 {"questionId":"%s","selectedIndex":4},{"questionId":"%s","selectedIndex":0}]}"""
			.formatted(ATTEMPT, PKG, Q.get(0), Q.get(0), Q.get(1), UUID.randomUUID());
		as(post("/api/v1/me/quiz-attempts").contentType(MediaType.APPLICATION_JSON).content(duplicate))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].code").value("DUPLICATE_ANSWER"))
			.andExpect(jsonPath("$.error.details[1].code").value("OUT_OF_RANGE"))
			.andExpect(jsonPath("$.error.details[2].code").value("UNKNOWN_QUESTION"));

		verify(this.repository, never()).insert(any());
	}

	@Test
	void idPackageAndPassMarkAreChecked() throws Exception {
		as(post("/api/v1/me/quiz-attempts").contentType(MediaType.APPLICATION_JSON).content("{\"answers\":[]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].field").value("id"))
			.andExpect(jsonPath("$.error.details[1].field").value("packageId"));

		given(this.repository.find(ATTEMPT)).willReturn(Optional.empty());
		as(post("/api/v1/me/quiz-attempts").contentType(MediaType.APPLICATION_JSON)
			.content(body(new int[] { 0, 1, 2, 3, 0 }, 101, "")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.details[0].field").value("passMarkPercent"));
	}

	@Test
	void anUnpublishedPackageIs404() throws Exception {
		given(this.repository.find(ATTEMPT)).willReturn(Optional.empty());
		given(this.repository.findAnswerKey(PKG)).willReturn(Optional.empty());

		as(post("/api/v1/me/quiz-attempts").contentType(MediaType.APPLICATION_JSON)
			.content(body(new int[] { 0, 1, 2, 3, 0 }, 60, "")))
			.andExpect(status().isNotFound());
	}

	@Test
	void listFiltersByPackage() throws Exception {
		given(this.repository.findPage(new PageQuery(DEVICE, PKG, null, null, 21)))
			.willReturn(List.of(stored(DEVICE, 4, true).view()));

		as(get("/api/v1/me/quiz-attempts?packageId=" + PKG)).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].answers.length()").value(1))
			.andExpect(jsonPath("$.items[0].answers[0].isCorrect").value(true))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	private static AnswerKey key(int i) {
		return new AnswerKey(Q.get(i), i % 4);
	}

	private static StoredAttempt stored(UUID device, int score, boolean passed) {
		return new StoredAttempt(device, new QuizAttemptView(ATTEMPT, PKG, score, 5, score * 20.0, 60, passed, T,
				passed, List.of(new QuizAttemptView.Answer(Q.get(0), 0, true))));
	}

	private static String body(int[] selected, Integer passMark, String extra) {
		StringBuilder answers = new StringBuilder();
		for (int i = 0; i < selected.length; i++) {
			if (i > 0) {
				answers.append(',');
			}
			answers.append("{\"questionId\":\"").append(Q.get(i)).append("\",\"selectedIndex\":").append(selected[i])
				.append('}');
		}
		return "{" + extra + "\"id\":\"" + ATTEMPT + "\",\"packageId\":\"" + PKG + "\",\"attemptedAt\":\"" + T + "\","
				+ (passMark == null ? "" : "\"passMarkPercent\":" + passMark + ",") + "\"answers\":[" + answers + "]}";
	}

	private ResultActions as(MockHttpServletRequestBuilder request) throws Exception {
		return this.mvc.perform(request.header(DeviceIdFilter.HEADER, DEVICE.toString()));
	}

}
