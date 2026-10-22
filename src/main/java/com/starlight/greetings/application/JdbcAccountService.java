package com.starlight.greetings.application;

import com.starlight.greetings.domain.auth.PasswordHasher;
import com.starlight.greetings.domain.auth.SignupValidator;
import com.starlight.greetings.web.ApiException;
import com.starlight.greetings.web.SignupResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JdbcAccountService implements AccountService {
 private final JdbcTemplate jdbc; private final RequestRateLimiter limiter; private final TransactionTemplate tx; private final PasswordHasher hashes = new PasswordHasher(); private final SignupValidator validator = new SignupValidator(); private final SecureRandom random = new SecureRandom();
 public JdbcAccountService(JdbcTemplate jdbc, RequestRateLimiter limiter, TransactionTemplate tx) { this.jdbc = jdbc; this.limiter=limiter; this.tx=tx; }
 // 횟수 제한(REQUIRES_NEW)은 트랜잭션 밖에서 먼저 센다. 안에서 부르면 요청마다 연결을 2개 잡아 동시 요청이 몰릴 때 연결 풀이 교착된다.
 @Override public String recover(String nicknameInput, String recoveryCode, String newPasswordInput) {
  var nickname = validator.nickname(nicknameInput); String newPassword = validator.password(newPasswordInput); limiter.consume("recover-account",nickname.key(),5);
  if (recoveryCode == null) throw invalidRecovery();
  String code = tx.execute(status -> {
   var users = jdbc.query("select id,recovery_code_hash from users where nickname_key=? and status='active' for update", (rs,i) -> new String[] {rs.getString(1),rs.getString(2)}, nickname.key());
   if (users.isEmpty() || !hashes.matches(users.get(0)[1], recoveryCode)) throw invalidRecovery();
   String next=token(); jdbc.update("update users set password_hash=?, recovery_code_hash=? where id=?::uuid", hashes.hash(newPassword), hashes.hash(next), users.get(0)[0]);
   jdbc.update("update sessions set revoked_at=now() where user_id=?::uuid and revoked_at is null", users.get(0)[0]); return next;
  });
  limiter.clear("recover-account",nickname.key()); return code;
 }
 @Transactional @Override public String rotateRecoveryCode(String userId, String passwordInput) {
  String password=validator.password(passwordInput); var users=jdbc.query("select password_hash from users where id=?::uuid and status='active' for update",(rs,i)->rs.getString(1),userId);
  if(users.isEmpty()||!hashes.matches(users.get(0),password)) throw credentials(); String code=token(); jdbc.update("update users set recovery_code_hash=? where id=?::uuid",hashes.hash(code),userId); return code;
 }
 @Transactional @Override public SignupResult changePassword(String userId, String currentInput, String nextInput) {
  String current=validator.password(currentInput), next=validator.password(nextInput); if(current.equals(next)) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PASSWORD","새 비밀번호는 현재 비밀번호와 다르게 입력해 주세요.");
  var users=jdbc.query("select nickname,password_hash from users where id=?::uuid and status='active' for update",(rs,i)->new String[]{rs.getString(1),rs.getString(2)},userId);
  if(users.isEmpty()||!hashes.matches(users.get(0)[1],current)) throw credentials(); jdbc.update("update users set password_hash=? where id=?::uuid",hashes.hash(next),userId); jdbc.update("update sessions set revoked_at=now() where user_id=?::uuid and revoked_at is null",userId);
  String session=token(),csrf=token(); jdbc.update("insert into sessions (id,user_id,token_hash,csrf_hash,csrf_token,expires_at) values (?::uuid,?::uuid,?,?,?,now()+interval '30 days')",UUID.randomUUID().toString(),userId,digest(session),digest(csrf),csrf); return new SignupResult(userId,users.get(0)[0],null,csrf,session);
 }
 @Transactional @Override public void withdraw(String userId, String passwordInput) {
  String password=validator.password(passwordInput); var hashesFound=jdbc.query("select password_hash from users where id=?::uuid and status='active' for update",(rs,i)->rs.getString(1),userId); if(hashesFound.isEmpty()||!hashes.matches(hashesFound.get(0),password)) throw credentials();
  jdbc.update("update users set status='withdrawn', withdrawn_at=now() where id=?::uuid",userId); jdbc.update("update letters set author_user_id=null where author_user_id=?::uuid",userId); jdbc.update("delete from request_keys where user_id=?::uuid",userId); jdbc.update("update reactions set user_id=null where user_id=?::uuid",userId); jdbc.update("delete from saves where user_id=?::uuid",userId); jdbc.update("update reports set reporter_user_id=null where reporter_user_id=?::uuid",userId); jdbc.update("update inquiries set requester_user_id=null where requester_user_id=?::uuid",userId); jdbc.update("update sessions set revoked_at=now() where user_id=?::uuid and revoked_at is null",userId);
 }
 private String token(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);} private String digest(String s){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}} private ApiException invalidRecovery(){return new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_RECOVERY","복구 정보를 확인해 주세요.");} private ApiException credentials(){return new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","비밀번호를 확인해 주세요.");}
}
