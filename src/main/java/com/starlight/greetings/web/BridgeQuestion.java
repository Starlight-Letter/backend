package com.starlight.greetings.web;

import java.time.Instant;

public record BridgeQuestion(String id, String body, Instant effectiveAt) {
}
