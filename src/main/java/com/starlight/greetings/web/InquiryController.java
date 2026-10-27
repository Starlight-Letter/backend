package com.starlight.greetings.web;
import com.starlight.greetings.application.InquiryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/inquiries") public class InquiryController {
 private final InquiryService inquiries; private final int trustedProxyHops;
 public InquiryController(InquiryService inquiries, @org.springframework.beans.factory.annotation.Value("${app.trusted-proxy-hops:0}") int trustedProxyHops) { this.inquiries=inquiries; this.trustedProxyHops=trustedProxyHops; }
 // 반복 문의 제한은 실제 접속자 IP 기준(CloudFront 뒤에서 직접 연결 주소를 쓰면 여러 사람이 한도를 나눠 쓰게 된다)
 @PostMapping public ResponseEntity<ApiEnvelope<Map<String,Object>>> submit(@RequestBody Map<String,Object> input,HttpServletRequest request) { String ip=ClientIp.of(request, trustedProxyHops); return ResponseEntity.status(201).body(ApiEnvelope.of(inquiries.submit(input, ip==null ? "unknown" : ip))); }
}
