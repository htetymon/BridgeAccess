package com.eduaccess.bridgeaccess.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.eduaccess.bridgeaccess.config.GeminiConfig;
import com.eduaccess.bridgeaccess.config.GeminiConfig.GeminiProperties;
import com.eduaccess.bridgeaccess.service.GeminiException.Kind;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Calls Gemini {@code generateContent} with structured JSON output and returns the parsed
 * lesson. One call, no retries: {@code LessonGenerator} validates and decides whether to try
 * again (FR-6.4). Runs on a generation worker, never on a request thread.
 */
@Service
public class GeminiService {

	private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

	static final String API_KEY_HEADER = "x-goog-api-key";

	/** What the lesson is built from. {@code sourceText} is pasted content or the read link. */
	public record LessonSource(String originalTitle, String author, String resourceLink, String resourceType,
			String sourceText) {
	}

	private final RestClient restClient;

	private final GeminiProperties properties;

	private final ObjectMapper objectMapper;

	public GeminiService(@Qualifier(GeminiConfig.REST_CLIENT) RestClient restClient, GeminiProperties properties,
			ObjectMapper objectMapper) {
		this.restClient = restClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	public GeneratedLesson generateLesson(LessonSource source) {
		return generateLesson(source, null);
	}

	/**
	 * @param correction what was wrong with the previous answer (see
	 * {@link LessonPrompt#correction}), or null on a first attempt; sent after the source,
	 * outside its fence
	 */
	public GeneratedLesson generateLesson(LessonSource source, String correction) {
		if (!this.properties.hasApiKey()) {
			throw new GeminiException(Kind.MISCONFIGURED, "GEMINI_API_KEY is not set");
		}
		long start = System.nanoTime();
		GenerateResponse response = call(requestBody(source, correction));
		String json = answerText(response);
		GeneratedLesson lesson = parse(json);
		logUsage(response, start);
		return lesson;
	}

	private Map<String, Object> requestBody(LessonSource source, String correction) {
		String user = LessonPrompt.userText(source.originalTitle(), source.author(), source.resourceLink(),
				source.resourceType(), source.sourceText());
		if (correction != null && !correction.isBlank()) {
			user = user + "\n\n" + correction;
		}
		return Map.of("systemInstruction", Map.of("parts", List.of(Map.of("text", LessonPrompt.SYSTEM_INSTRUCTION))),
				"contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))),
				"generationConfig",
				Map.of("responseMimeType", "application/json", "responseJsonSchema", LessonPrompt.responseSchema(),
						"temperature", this.properties.temperature(), "maxOutputTokens",
						this.properties.maxOutputTokens()));
	}

	private GenerateResponse call(Map<String, Object> body) {
		try {
			GenerateResponse response = this.restClient.post()
				.uri("/v1beta/models/{model}:generateContent", this.properties.model())
				.header(API_KEY_HEADER, this.properties.apiKey())
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(GenerateResponse.class);
			if (response == null) {
				throw new GeminiException(Kind.INVALID_OUTPUT, "empty response body");
			}
			return response;
		}
		catch (RestClientResponseException ex) {
			throw fromStatus(ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
		}
		catch (ResourceAccessException ex) {
			throw new GeminiException(Kind.UNAVAILABLE, "network or timeout: " + ex.getMessage(), ex);
		}
		catch (RestClientException ex) {
			// For example a 200 whose body is not the expected JSON.
			throw new GeminiException(Kind.INVALID_OUTPUT, "unreadable response: " + ex.getMessage(), ex);
		}
	}

	private GeminiException fromStatus(HttpStatusCode status, String body, Exception cause) {
		// Google's error body explains the problem and contains no secrets; cap it for the log.
		String detail = status.value() + " " + abbreviate(body);
		int code = status.value();
		if (code == 429 || code == 408 || status.is5xxServerError()) {
			return new GeminiException(Kind.UNAVAILABLE, detail, cause);
		}
		return new GeminiException(Kind.MISCONFIGURED, detail, cause); // 400, 401, 403, 404
	}

	private static String answerText(GenerateResponse response) {
		if (response.promptFeedback() != null && response.promptFeedback().blockReason() != null) {
			throw new GeminiException(Kind.BLOCKED, "prompt blocked: " + response.promptFeedback().blockReason());
		}
		if (response.candidates() == null || response.candidates().isEmpty()) {
			throw new GeminiException(Kind.INVALID_OUTPUT, "no candidates");
		}
		Candidate candidate = response.candidates().get(0);
		String finish = candidate.finishReason();
		if (finish != null && !"STOP".equals(finish)) {
			Kind kind = switch (finish) {
				case "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> Kind.BLOCKED;
				default -> Kind.INVALID_OUTPUT; // MAX_TOKENS, RECITATION, MALFORMED_RESPONSE, ...
			};
			throw new GeminiException(kind, "finishReason " + finish);
		}
		String text = candidate.content() == null || candidate.content().parts() == null ? ""
				: candidate.content()
					.parts()
					.stream()
					.filter(part -> !Boolean.TRUE.equals(part.thought()))
					.map(Part::text)
					.filter(Objects::nonNull)
					.collect(Collectors.joining());
		if (text.isBlank()) {
			throw new GeminiException(Kind.INVALID_OUTPUT, "empty answer");
		}
		return text;
	}

	private GeneratedLesson parse(String json) {
		String trimmed = json.strip();
		if (trimmed.startsWith("```")) {
			// Not expected with responseMimeType=application/json, but cheap to tolerate.
			trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
		}
		try {
			GeneratedLesson lesson = this.objectMapper.readValue(trimmed, GeneratedLesson.class);
			if (lesson == null) {
				throw new GeminiException(Kind.INVALID_OUTPUT, "answer is JSON null");
			}
			return lesson;
		}
		catch (JacksonException ex) {
			throw new GeminiException(Kind.INVALID_OUTPUT, "answer is not the lesson JSON: " + ex.getOriginalMessage(),
					ex);
		}
	}

	private void logUsage(GenerateResponse response, long start) {
		Usage usage = response.usageMetadata();
		long millis = (System.nanoTime() - start) / 1_000_000;
		if (usage == null) {
			log.info("Gemini {} answered in {} ms", this.properties.model(), millis);
			return;
		}
		log.info("Gemini {} answered in {} ms: {} prompt, {} answer, {} thinking, {} total tokens",
				this.properties.model(), millis, usage.promptTokenCount(), usage.candidatesTokenCount(),
				usage.thoughtsTokenCount(), usage.totalTokenCount());
	}

	private static String abbreviate(String text) {
		if (text == null) {
			return "";
		}
		String oneLine = text.replaceAll("\\s+", " ").strip();
		return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500) + "...";
	}

	// The parts of the generateContent response this service reads; the rest is ignored.

	record GenerateResponse(List<Candidate> candidates, PromptFeedback promptFeedback, Usage usageMetadata) {
	}

	record Candidate(Content content, String finishReason) {
	}

	record Content(List<Part> parts) {
	}

	record Part(String text, Boolean thought) {
	}

	record PromptFeedback(String blockReason) {
	}

	record Usage(Integer promptTokenCount, Integer candidatesTokenCount, Integer thoughtsTokenCount,
			Integer totalTokenCount) {
	}

}
