package com.starlight.greetings.application;
import com.starlight.greetings.web.ApiException;
import com.starlight.greetings.web.SessionView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service public class JdbcSessionService implements SessionService {
 private final JdbcTemplate jdbc; public JdbcSessionService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public SessionView current(String token){ if(token==null) throw new ApiException(HttpStatus.UNAUTHORIZED,"LOGIN_REQUIRED","로그인이 필요합니다."); var rows=jdbc.query("select u.id,u.nickname,u.role,s.csrf_token from sessions s join users u on u.id=s.user_id where s.token_hash=? and s.revoked_at is null and s.expires_at>now() and u.status='active'",(r,i)->new SessionView(r.getString("id"),r.getString("nickname"),r.getString("role"),r.getString("csrf_token")),digest(token)); if(rows.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED,"LOGIN_REQUIRED","로그인이 필요합니다."); return rows.get(0); }
 private String digest(String v){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
