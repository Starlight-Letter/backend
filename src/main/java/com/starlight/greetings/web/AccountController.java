package com.starlight.greetings.web;
import com.starlight.greetings.application.AccountService;
import com.starlight.greetings.application.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1") public class AccountController {
 private final AccountService accounts; private final SessionService sessions;
 public AccountController(AccountService accounts,SessionService sessions){this.accounts=accounts;this.sessions=sessions;}
 @PostMapping("/auth/recover") public ApiEnvelope<Map<String,String>> recover(@RequestBody Map<String,String> body){return ApiEnvelope.of(Map.of("recoveryCode",accounts.recover(body.get("nickname"),body.get("recoveryCode"),body.get("newPassword"))));}
 @PostMapping("/me/recovery-code/rotate") public ApiEnvelope<Map<String,String>> rotate(@RequestBody Map<String,String> body,@RequestHeader(value="x-csrf-token",required=false) String csrf,HttpServletRequest req){var user=sessions.member(SessionCookie.token(req),csrf);return ApiEnvelope.of(Map.of("recoveryCode",accounts.rotateRecoveryCode(user.id(),body.get("password"))));}
 @PostMapping("/me/password") public ResponseEntity<ApiEnvelope<Map<String,Object>>> password(@RequestBody Map<String,String> body,@RequestHeader(value="x-csrf-token",required=false) String csrf,HttpServletRequest req){var user=sessions.member(SessionCookie.token(req),csrf);SignupResult result=accounts.changePassword(user.id(),body.get("currentPassword"),body.get("newPassword"));return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie(result.sessionToken(),false)).body(ApiEnvelope.of(Map.of("user",Map.of("id",result.id(),"nickname",result.nickname(),"role","member"),"csrfToken",result.csrfToken())));}
 @PostMapping("/me/withdraw") public ResponseEntity<ApiEnvelope<Map<String,Boolean>>> withdraw(@RequestBody Map<String,String> body,@RequestHeader(value="x-csrf-token",required=false) String csrf,HttpServletRequest req){var user=sessions.member(SessionCookie.token(req),csrf);accounts.withdraw(user.id(),body.get("password"));return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie("",true)).body(ApiEnvelope.of(Map.of("withdrawn",true)));}
 private String cookie(String value,boolean clear){return "star_session_dev="+value+"; Path=/; HttpOnly; SameSite=Lax; Max-Age="+(clear?0:2592000);}
}
