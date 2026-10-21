package com.starlight.greetings.web;
import com.starlight.greetings.application.BridgePassService; import com.starlight.greetings.application.JdbcBridgePassService; import com.starlight.greetings.application.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap; import java.util.List; import java.util.Map;
import org.springframework.http.HttpHeaders; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
// 다리 QR 작성 허가. 비회원도 QR을 찍을 수 있어야 가입 후 바로 쓸 수 있으므로 세션과 따로 쿠키로 둔다.
@RestController @RequestMapping("/api/v1") public class BridgePassController {
 static final String COOKIE="star_bridge_pass";
 private final BridgePassService passes;private final SessionService sessions;
 public BridgePassController(BridgePassService passes,SessionService sessions){this.passes=passes;this.sessions=sessions;}
 @PostMapping("/bridges/{bridgeId}/pass") public ResponseEntity<ApiEnvelope<Map<String,Object>>> issue(@PathVariable String bridgeId,@RequestBody Map<String,String> body,HttpServletRequest request){
  var issued=new LinkedHashMap<>(passes.issue(bridgeId,body.get("key"),token(request)));String token=(String)issued.remove("token");
  return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,COOKIE+"="+token+"; Path=/; HttpOnly; SameSite=Lax; Max-Age="+JdbcBridgePassService.PASS_TTL.toSeconds()).body(ApiEnvelope.of(issued));
 }
 @GetMapping("/bridge-pass") public ApiEnvelope<Map<String,Object>> current(HttpServletRequest request){return ApiEnvelope.of(passes.current(token(request)));}
 @GetMapping("/admin/bridge-qr") public ApiEnvelope<List<Map<String,Object>>> qrKeys(HttpServletRequest request){sessions.adminRead(SessionCookie.token(request));return ApiEnvelope.of(passes.qrKeys());}
 @PostMapping("/admin/bridges/{bridgeId}/qr-key/rotate") public ApiEnvelope<Map<String,Object>> rotate(@PathVariable String bridgeId,@RequestBody Map<String,String> body,@RequestHeader(value="x-csrf-token",required=false) String csrf,HttpServletRequest request){var admin=sessions.admin(SessionCookie.token(request),csrf);return ApiEnvelope.of(passes.rotate(bridgeId,body.get("reason"),admin.id()));}
 static String token(HttpServletRequest request){if(request.getCookies()!=null)for(var cookie:request.getCookies())if(COOKIE.equals(cookie.getName()))return cookie.getValue();return null;}
}
