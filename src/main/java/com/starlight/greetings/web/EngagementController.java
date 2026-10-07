package com.starlight.greetings.web;

import com.starlight.greetings.application.EngagementService;
import com.starlight.greetings.application.SessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class EngagementController {
    private final EngagementService engagement;
    private final SessionService sessions;
    public EngagementController(EngagementService engagement, SessionService sessions) { this.engagement = engagement; this.sessions = sessions; }

    @GetMapping("/best") public ApiEnvelope<List<Map<String, Object>>> best(@RequestParam String scope, @RequestParam(required = false) String bridgeId, @RequestParam String period) {
        return ApiEnvelope.of(engagement.best(scope, bridgeId, period));
    }
    @GetMapping("/me/saved") public ApiEnvelope<List<Map<String, Object>>> saved(HttpServletRequest request) {
        return ApiEnvelope.of(engagement.saved(sessions.current(token(request)).id()));
    }
    @GetMapping("/letters/{letterId}/engagement") public ApiEnvelope<Map<String, Object>> state(@PathVariable String letterId, HttpServletRequest request) {
        return ApiEnvelope.of(engagement.state(letterId, sessions.current(token(request)).id()));
    }
    @PutMapping("/letters/{letterId}/reaction") public ApiEnvelope<Map<String, Object>> react(@PathVariable String letterId, @RequestHeader(value = "x-csrf-token", required = false) String csrf, HttpServletRequest request) {
        return ApiEnvelope.of(engagement.react(letterId, sessions.member(token(request), csrf).id(), true));
    }
    @DeleteMapping("/letters/{letterId}/reaction") public ApiEnvelope<Map<String, Object>> unreact(@PathVariable String letterId, @RequestHeader(value = "x-csrf-token", required = false) String csrf, HttpServletRequest request) {
        return ApiEnvelope.of(engagement.react(letterId, sessions.member(token(request), csrf).id(), false));
    }
    @PutMapping("/letters/{letterId}/save") public ApiEnvelope<Map<String, Object>> save(@PathVariable String letterId, @RequestHeader(value = "x-csrf-token", required = false) String csrf, HttpServletRequest request) {
        return ApiEnvelope.of(engagement.save(letterId, sessions.member(token(request), csrf).id(), true));
    }
    @DeleteMapping("/letters/{letterId}/save") public ApiEnvelope<Map<String, Object>> unsave(@PathVariable String letterId, @RequestHeader(value = "x-csrf-token", required = false) String csrf, HttpServletRequest request) {
        return ApiEnvelope.of(engagement.save(letterId, sessions.member(token(request), csrf).id(), false));
    }
    private String token(HttpServletRequest request) { return token(request, null); }
    private String token(HttpServletRequest request, String ignored) { if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) if ("star_session_dev".equals(cookie.getName())) return cookie.getValue(); return null; }
}
