package com.starlight.greetings.domain.questions;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public class QuestionSelector {
    public Question currentQuestion(List<Question> questions, Instant now) {
        return questions.stream()
            .filter(question -> "published".equals(question.status()))
            .filter(question -> !question.effectiveAt().isAfter(now))
            .max(Comparator.comparing(Question::effectiveAt))
            .orElse(null);
    }
}
