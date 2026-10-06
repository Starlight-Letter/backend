package com.starlight.greetings.domain.questions;

import java.time.Instant;

public record Question(String body, Instant effectiveAt, String status) {
}
