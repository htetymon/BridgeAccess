package com.eduaccess.bridgeaccess.service.generation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.service.GeneratedLesson;
import com.eduaccess.bridgeaccess.service.GeneratedLesson.KeyConcept;
import com.eduaccess.bridgeaccess.service.GeneratedLesson.QuizQuestion;
import com.eduaccess.bridgeaccess.service.LessonPrompt;

/**
 * Checks Gemini's lesson before it can be published (FR-6.4): exactly 5 questions, 4
 * different non-empty options each, an answer index from 0 to 3, an allowed subject, and
 * every field the database requires to be present and within its counts.
 *
 * <p>
 * The response schema already asks the API to enforce counts, the enum and the index range,
 * but this is the guarantee: the schema is a request to the model, the database constraints
 * are not negotiable, and the learner should never see a broken quiz.
 *
 * <p>
 * Problems are written as plain instructions, so they can be sent back to the model on the
 * retry and also read well in the log. They are never shown to the contributor.
 */
@Component
public class LessonValidator {

	static final int QUIZ_QUESTIONS = 5;

	static final int OPTIONS = 4;

	/** The prompt asks for 80; a little over is harmless, a paragraph is not a title. */
	static final int MAX_TITLE_CHARS = 100;

	/** Longest model text quoted back in a problem, so the retry prompt stays small. */
	private static final int QUOTE_CHARS = 40;

	/** The outcome: the lesson with text trimmed and subject spelled as the database wants. */
	public record Validation(GeneratedLesson lesson, List<String> problems) {

		public boolean isValid() {
			return this.problems.isEmpty();
		}

	}

	public Validation validate(GeneratedLesson raw) {
		List<String> problems = new ArrayList<>();
		if (raw == null) {
			return new Validation(null, List.of("the answer contained no lesson"));
		}

		String title = trim(raw.title());
		if (title.isEmpty()) {
			problems.add("title is empty");
		}
		else if (title.length() > MAX_TITLE_CHARS) {
			problems.add("title is " + title.length() + " characters; keep it to 80 or fewer");
		}

		Optional<String> subject = canonicalSubject(raw.subject());
		if (subject.isEmpty()) {
			problems.add("subject \"" + quote(raw.subject()) + "\" is not one of "
					+ String.join(", ", LessonPrompt.SUBJECTS));
		}

		String overview = requireText(raw.overview(), "overview", problems);
		String summary = requireText(raw.summary(), "summary", problems);
		List<String> takeaways = takeaways(raw.keyTakeaways(), problems);
		List<KeyConcept> concepts = concepts(raw.keyConcepts(), problems);
		List<QuizQuestion> quiz = quiz(raw.quiz(), problems);

		GeneratedLesson clean = new GeneratedLesson(title, subject.orElse(raw.subject()), overview, takeaways,
				summary, concepts, quiz);
		return new Validation(clean, List.copyOf(problems));
	}

	/** Case and spaces are forgiven ("science " is Science); anything else is not. */
	static Optional<String> canonicalSubject(String subject) {
		String wanted = trim(subject);
		return LessonPrompt.SUBJECTS.stream().filter(allowed -> allowed.equalsIgnoreCase(wanted)).findFirst();
	}

	private static List<String> takeaways(List<String> raw, List<String> problems) {
		if (raw == null) {
			problems.add("keyTakeaways is missing; give 3 to 5");
			return List.of();
		}
		if (raw.size() < 3 || raw.size() > 5) {
			problems.add("keyTakeaways has " + raw.size() + " items; give 3 to 5");
		}
		List<String> clean = new ArrayList<>();
		for (int i = 0; i < raw.size(); i++) {
			clean.add(requireText(raw.get(i), "keyTakeaways[" + (i + 1) + "]", problems));
		}
		return clean;
	}

	private static List<KeyConcept> concepts(List<KeyConcept> raw, List<String> problems) {
		if (raw == null) {
			problems.add("keyConcepts is missing; give 4 to 6");
			return List.of();
		}
		if (raw.size() < 4 || raw.size() > 6) {
			problems.add("keyConcepts has " + raw.size() + " items; give 4 to 6");
		}
		List<KeyConcept> clean = new ArrayList<>();
		for (int i = 0; i < raw.size(); i++) {
			String where = "keyConcepts[" + (i + 1) + "]";
			KeyConcept concept = raw.get(i);
			if (concept == null) {
				problems.add(where + " is empty");
				continue;
			}
			clean.add(new KeyConcept(requireText(concept.term(), where + ".term", problems),
					requireText(concept.explanation(), where + ".explanation", problems)));
		}
		return clean;
	}

	private static List<QuizQuestion> quiz(List<QuizQuestion> raw, List<String> problems) {
		if (raw == null) {
			problems.add("quiz is missing; give exactly " + QUIZ_QUESTIONS + " questions");
			return List.of();
		}
		if (raw.size() != QUIZ_QUESTIONS) {
			problems.add("quiz has " + raw.size() + " questions; give exactly " + QUIZ_QUESTIONS);
		}
		List<QuizQuestion> clean = new ArrayList<>();
		Set<String> seenQuestions = new HashSet<>();
		for (int i = 0; i < raw.size(); i++) {
			String where = "quiz[" + (i + 1) + "]";
			QuizQuestion question = raw.get(i);
			if (question == null) {
				problems.add(where + " is empty");
				continue;
			}
			String text = requireText(question.question(), where + ".question", problems);
			if (!text.isEmpty() && !seenQuestions.add(key(text))) {
				problems.add(where + " repeats an earlier question");
			}
			List<String> options = options(question.options(), where, problems);
			Integer index = question.correctIndex();
			if (index == null) {
				problems.add(where + ".correctIndex is missing; give 0 to 3");
			}
			else if (index < 0 || index >= OPTIONS) {
				problems.add(where + ".correctIndex is " + index + "; give 0 to 3");
			}
			String explanation = requireText(question.explanation(), where + ".explanation", problems);
			clean.add(new QuizQuestion(text, options, index, explanation));
		}
		return clean;
	}

	private static List<String> options(List<String> raw, String where, List<String> problems) {
		if (raw == null) {
			problems.add(where + ".options is missing; give exactly " + OPTIONS);
			return List.of();
		}
		if (raw.size() != OPTIONS) {
			problems.add(where + ".options has " + raw.size() + " items; give exactly " + OPTIONS);
		}
		List<String> clean = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		boolean duplicate = false;
		for (int i = 0; i < raw.size(); i++) {
			String option = requireText(raw.get(i), where + ".options[" + (i + 1) + "]", problems);
			if (!option.isEmpty() && !seen.add(key(option))) {
				duplicate = true;
			}
			clean.add(option);
		}
		if (duplicate) {
			// Two identical options make the question unanswerable or give the answer away.
			problems.add(where + ".options are not all different");
		}
		return clean;
	}

	private static String requireText(String value, String field, List<String> problems) {
		String clean = trim(value);
		if (clean.isEmpty()) {
			problems.add(field + " is empty");
		}
		return clean;
	}

	private static String trim(String value) {
		return value == null ? "" : value.strip();
	}

	private static String key(String text) {
		return text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private static String quote(String value) {
		String clean = trim(value).replaceAll("\\s+", " ");
		return clean.length() <= QUOTE_CHARS ? clean : clean.substring(0, QUOTE_CHARS) + "...";
	}

}
