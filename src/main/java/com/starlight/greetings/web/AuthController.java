package com.starlight.greetings.web;

import com.starlight.greetings.application.SignupService;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final SignupService signupService;
    public AuthController(SignupService signupService) { this.signupService = signupService; }

    @PostMapping("/signup")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> signup(@RequestBody Map<String, String> body) {
        SignupResult result = signupService.signup(body.get("nickname"), body.get("password"));
        return ResponseEntity.status(201)
            .header(HttpHeaders.SET_COOKIE, "star_session_dev=" + result.sessionToken() + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000")
            .body(ApiEnvelope.of(Map.of("user", Map.of("id", result.id(), "nickname", result.nickname(), "role", "member"), "recoveryCode", result.recoveryCode(), "csrfToken", result.csrfToken())));
    }
}
