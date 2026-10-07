package com.starlight.greetings.web;

public record SignupResult(String id, String nickname, String recoveryCode, String csrfToken, String sessionToken) {
}
