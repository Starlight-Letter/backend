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
 public void logout(String token,String csrf){ SessionView view=current(token); if(csrf==null || !csrf.equals(view.csrfToken())) throw new ApiException(HttpStatus.FORBIDDEN,"CSRF_REQUIRED","요청 인증 정보가 올바르지 않습니다."); jdbc.update("update sessions set revoked_at=now() where token_hash=?",digest(token)); }
 public SessionView member(String token,String csrf){SessionView view=current(token);if(csrf==null||!csrf.equals(view.csrfToken()))throw new ApiException(HttpStatus.FORBIDDEN,"CSRF_REQUIRED","요청 인증 정보가 올바르지 않습니다.");if(!"member".equals(view.role()))throw new ApiException(HttpStatus.FORBIDDEN,"MEMBER_REQUIRED","회원 기능을 이용할 수 없습니다.");return view;} public SessionView admin(String token,String csrf){SessionView view=current(token);if(csrf==null||!csrf.equals(view.csrfToken()))throw new ApiException(HttpStatus.FORBIDDEN,"CSRF_REQUIRED","요청 인증 정보가 올바르지 않습니다.");Boolean verified=jdbc.queryForObject("select exists(select 1 from sessions s join users u on u.id=s.user_id where s.token_hash=? and s.admin_verified_at is not null and u.role=\'admin\')",Boolean.class,digest(token));if(!Boolean.TRUE.equals(verified))throw new ApiException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","운영자 권한이 필요합니다.");return view;}
 private String digest(String v){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
