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
    private final com.starlight.greetings.application.LoginService loginService;
    public AuthController(SignupService signupService, com.starlight.greetings.application.LoginService loginService) { this.signupService = signupService; this.loginService = loginService; }

    @PostMapping("/signup")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> signup(@RequestBody Map<String, String> body) {
        SignupResult result = signupService.signup(body.get("nickname"), body.get("password"));
        return ResponseEntity.status(201)
            .header(HttpHeaders.SET_COOKIE, "star_session_dev=" + result.sessionToken() + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000")
            .body(ApiEnvelope.of(Map.of("user", Map.of("id", result.id(), "nickname", result.nickname(), "role", "member"), "recoveryCode", result.recoveryCode(), "csrfToken", result.csrfToken())));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> login(@RequestBody Map<String, String> body) {
        SignupResult result = loginService.login(body.get("nickname"), body.get("password"));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, "star_session_dev=" + result.sessionToken() + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000")
            .body(ApiEnvelope.of(Map.of("user", Map.of("id", result.id(), "nickname", result.nickname(), "role", "member"), "csrfToken", result.csrfToken())));
    }
}
