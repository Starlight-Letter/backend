package com.starlight.greetings.web;
import java.time.Instant;
public record LetterDetail(String id, String bridgeId, String displayNickname, String body, int theme, boolean isSeed, Instant createdAt, String questionBody) { }
