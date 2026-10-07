package com.starlight.greetings.web;
import com.starlight.greetings.application.ReportService;
import com.starlight.greetings.application.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/letters/{letterId}/reports") public class ReportController {
 private final ReportService reports; private final SessionService sessions;
 public ReportController(ReportService reports, SessionService sessions) { this.reports=reports; this.sessions=sessions; }
 @PostMapping public ResponseEntity<ApiEnvelope<Map<String,Object>>> report(@PathVariable String letterId,@RequestBody Map<String,Object> input,@RequestHeader(value="x-csrf-token",required=false) String csrf,HttpServletRequest request) { var user=sessions.member(SessionCookie.token(request),csrf); return ResponseEntity.status(201).body(ApiEnvelope.of(reports.report(letterId,user.id(),input.get("reasonCode") instanceof String reason ? reason : null))); }
}
