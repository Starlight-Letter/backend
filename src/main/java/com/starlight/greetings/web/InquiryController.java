package com.starlight.greetings.web;
import com.starlight.greetings.application.InquiryService;
import com.starlight.greetings.application.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/inquiries") public class InquiryController {
 private final InquiryService inquiries; private final SessionService sessions;
 public InquiryController(InquiryService inquiries, SessionService sessions) { this.inquiries=inquiries; this.sessions=sessions; }
 @PostMapping public ResponseEntity<ApiEnvelope<Map<String,Object>>> submit(@RequestBody Map<String,Object> input,@RequestHeader(value="x-csrf-token",required=false) String csrf,HttpServletRequest request) { if ("removal".equals(input.get("type"))) input.put("requesterUserId",sessions.member(SessionCookie.token(request),csrf).id()); return ResponseEntity.status(201).body(ApiEnvelope.of(inquiries.submit(input, request.getRemoteAddr()==null ? "unknown" : request.getRemoteAddr()))); }
}
