package com.starlight.greetings.web;

import java.time.Instant;

public record LetterPreview(String id, String bridgeId, int theme, boolean isSeed, String preview, Instant createdAt) {
}
