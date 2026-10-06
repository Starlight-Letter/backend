package com.starlight.greetings.web;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
    @GetMapping("/health")
    public ResponseEntity<ApiEnvelope<Map<String, String>>> health() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(Map.of("status", "ok")));
    }
}
