package com.eduaccess.bridgeaccess.service.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.eduaccess.bridgeaccess.service.GeneratedLesson;
import com.eduaccess.bridgeaccess.service.GeneratedLesson.KeyConcept;
import com.eduaccess.bridgeaccess.service.GeneratedLesson.QuizQuestion;
import com.eduaccess.bridgeaccess.service.generation.LessonValidator.Validation;

class LessonValidatorTest {

	private final LessonValidator validator = new LessonValidator();

	static GeneratedLesson valid() {
		List<QuizQuestion> quiz = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			quiz.add(new QuizQuestion("Question " + i + "?", List.of("A" + i, "B" + i, "C" + i, "D" + i), i % 4,
					"Because the source says so."));
		}
		return new GeneratedLesson("Clean Energy Basics", "Science", "You will learn about clean energy.",
				List.of("One.", "Two.", "Three."), "Renewable energy refills naturally.",
				List.of(new KeyConcept("Solar", "Sunlight."), new KeyConcept("Wind", "Moving air."),
						new KeyConcept("Hydro", "Flowing water."), new KeyConcept("Geothermal", "Earth heat.")),
				quiz);
	}

	@Test
	void acceptsAValidLesson() {
		Validation result = this.validator.validate(valid());

		assertThat(result.isValid()).as(result.problems().toString()).isTrue();
		assertThat(result.lesson()).isEqualTo(valid());
	}

	@Test
	void trimsTextAndFixesSubjectCase() {
		GeneratedLesson raw = valid();
		Validation result = this.validator.validate(new GeneratedLesson("  Clean Energy Basics \n", " science ",
				raw.overview(), raw.keyTakeaways(), raw.summary(), raw.keyConcepts(), withQuestion(raw.quiz(), 0,
						q -> new QuizQuestion(" Q? ", List.of(" a", "b ", "c", "d"), 1, " Why. "))));

		assertThat(result.isValid()).as(result.problems().toString()).isTrue();
		assertThat(result.lesson().title()).isEqualTo("Clean Energy Basics");
		assertThat(result.lesson().subject()).isEqualTo("Science");
		assertThat(result.lesson().quiz().get(0)).isEqualTo(new QuizQuestion("Q?", List.of("a", "b", "c", "d"), 1, "Why."));
	}

	@ParameterizedTest
	@ValueSource(strings = { "Biology", "", "Science, Health" })
	void rejectsSubjectOutsideTheList(String subject) {
		GeneratedLesson raw = valid();

		assertProblem(new GeneratedLesson(raw.title(), subject, raw.overview(), raw.keyTakeaways(), raw.summary(),
				raw.keyConcepts(), raw.quiz()), "subject");
	}

	@Test
	void rejectsMissingSubject() {
		GeneratedLesson raw = valid();

		assertProblem(new GeneratedLesson(raw.title(), null, raw.overview(), raw.keyTakeaways(), raw.summary(),
				raw.keyConcepts(), raw.quiz()), "subject");
	}

	@Test
	void rejectsWrongQuestionCount() {
		assertProblem(withQuiz(valid().quiz().subList(0, 4)), "quiz has 4 questions; give exactly 5");
		List<QuizQuestion> six = new ArrayList<>(valid().quiz());
		six.add(new QuizQuestion("Extra?", List.of("w", "x", "y", "z"), 0, "Why."));
		assertProblem(withQuiz(six), "quiz has 6 questions; give exactly 5");
		assertProblem(withQuiz(null), "quiz is missing");
	}

	@Test
	void rejectsWrongOptionCount() {
		assertProblem(question(q -> new QuizQuestion(q.question(), List.of("a", "b", "c"), 0, q.explanation())),
				"quiz[1].options has 3 items; give exactly 4");
		assertProblem(question(q -> new QuizQuestion(q.question(), List.of("a", "b", "c", "d", "e"), 0, q.explanation())),
				"quiz[1].options has 5 items; give exactly 4");
		assertProblem(question(q -> new QuizQuestion(q.question(), null, 0, q.explanation())), "quiz[1].options is missing");
	}

	@Test
	void rejectsBlankOrRepeatedOptions() {
		assertProblem(question(q -> new QuizQuestion(q.question(), Arrays.asList("a", " ", "c", null), 0, q.explanation())),
				"quiz[1].options[2] is empty");
		assertProblem(question(q -> new QuizQuestion(q.question(), List.of("Solar", "wind", "SOLAR ", "tide"), 0,
				q.explanation())), "quiz[1].options are not all different");
	}

	@ParameterizedTest
	@ValueSource(ints = { -1, 4, 9 })
	void rejectsAnswerIndexOutOfRange(int index) {
		assertProblem(question(q -> new QuizQuestion(q.question(), q.options(), index, q.explanation())),
				"quiz[1].correctIndex is " + index + "; give 0 to 3");
	}

	@Test
	void rejectsMissingAnswerIndex() {
		assertProblem(question(q -> new QuizQuestion(q.question(), q.options(), null, q.explanation())),
				"quiz[1].correctIndex is missing");
	}

	@Test
	void rejectsBlankQuestionOrExplanationAndRepeats() {
		assertProblem(question(q -> new QuizQuestion(" ", q.options(), 0, q.explanation())), "quiz[1].question is empty");
		assertProblem(question(q -> new QuizQuestion(q.question(), q.options(), 0, "")), "quiz[1].explanation is empty");
		assertProblem(withQuiz(withQuestion(valid().quiz(), 1,
				q -> new QuizQuestion("question 0? ", q.options(), 0, q.explanation()))), "quiz[2] repeats an earlier question");
	}

	@Test
	void rejectsWhatTheDatabaseWouldRefuse() {
		GeneratedLesson raw = valid();
		Validation result = this.validator.validate(new GeneratedLesson("", raw.subject(), " ", List.of("One.", "Two."),
				null, raw.keyConcepts().subList(0, 3), raw.quiz()));

		assertThat(result.problems()).contains("title is empty", "overview is empty", "summary is empty",
				"keyTakeaways has 2 items; give 3 to 5", "keyConcepts has 3 items; give 4 to 6");
	}

	@Test
	void rejectsOverlongTitle() {
		GeneratedLesson raw = valid();

		assertProblem(new GeneratedLesson("x".repeat(101), raw.subject(), raw.overview(), raw.keyTakeaways(),
				raw.summary(), raw.keyConcepts(), raw.quiz()), "title is 101 characters");
	}

	@Test
	void collectsEveryProblemAtOnce() {
		GeneratedLesson raw = valid();
		Validation result = this.validator.validate(new GeneratedLesson(raw.title(), "Cooking", raw.overview(),
				raw.keyTakeaways(), raw.summary(), raw.keyConcepts(), withQuestion(raw.quiz().subList(0, 4), 2,
						q -> new QuizQuestion(q.question(), List.of("a", "b"), 7, q.explanation()))));

		assertThat(result.isValid()).isFalse();
		assertThat(result.problems()).hasSize(4);
	}

	@Test
	void quotesLongModelTextShortly() {
		GeneratedLesson raw = valid();
		Validation result = this.validator.validate(new GeneratedLesson(raw.title(), "y".repeat(500), raw.overview(),
				raw.keyTakeaways(), raw.summary(), raw.keyConcepts(), raw.quiz()));

		assertThat(result.problems()).singleElement().asString().hasSizeLessThan(150);
	}

	@Test
	void nullLesson() {
		assertThat(this.validator.validate(null).isValid()).isFalse();
	}

	private void assertProblem(GeneratedLesson lesson, String expected) {
		Validation result = this.validator.validate(lesson);
		assertThat(result.isValid()).isFalse();
		assertThat(result.problems()).anySatisfy(problem -> assertThat(problem).contains(expected));
	}

	private static GeneratedLesson withQuiz(List<QuizQuestion> quiz) {
		GeneratedLesson raw = valid();
		return new GeneratedLesson(raw.title(), raw.subject(), raw.overview(), raw.keyTakeaways(), raw.summary(),
				raw.keyConcepts(), quiz);
	}

	private static GeneratedLesson question(UnaryOperator<QuizQuestion> change) {
		return withQuiz(withQuestion(valid().quiz(), 0, change));
	}

	private static List<QuizQuestion> withQuestion(List<QuizQuestion> quiz, int index, UnaryOperator<QuizQuestion> change) {
		List<QuizQuestion> copy = new ArrayList<>(quiz);
		copy.set(index, change.apply(copy.get(index)));
		return copy;
	}

}
