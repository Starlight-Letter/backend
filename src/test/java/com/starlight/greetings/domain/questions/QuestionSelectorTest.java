package com.starlight.greetings.domain.questions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionSelectorTest {
    @Test
    void keepsTheMostRecentPublishedQuestionUntilTheNextQuestionStarts() {
        QuestionSelector selector = new QuestionSelector();
        var questions = List.of(
            new Question("지난주", Instant.parse("2026-10-04T15:00:00Z"), "published"),
            new Question("다음주", Instant.parse("2026-10-11T15:00:00Z"), "published")
        );

        assertEquals("지난주", selector.currentQuestion(questions, Instant.parse("2026-10-06T00:00:00Z")).body());
    }
}
