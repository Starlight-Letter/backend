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

@Service
public class JdbcLoginService implements LoginService {
    private final JdbcTemplate jdbc; private final RequestRateLimiter limiter; private final SignupValidator validator = new SignupValidator(); private final PasswordHasher hasher = new PasswordHasher(); private final SecureRandom random = new SecureRandom();
    public JdbcLoginService(JdbcTemplate jdbc, RequestRateLimiter limiter) { this.jdbc = jdbc; this.limiter=limiter; }
    // 트랜잭션으로 묶지 않는다: 횟수 제한(REQUIRES_NEW)이 연결을 하나 더 잡아 동시 로그인이 몰리면 연결 풀이 교착되고,
    // 비밀번호 확인(Argon2) 동안 연결을 쥐고 있을 이유도 없다. 쓰기는 세션 한 줄뿐이라 원자성도 필요 없다.
    @Override public SignupResult login(String nicknameInput, String passwordInput) {
        var nickname = validator.nickname(nicknameInput); String password = validator.password(passwordInput); limiter.consume("login-account",nickname.key(),5);
        var users = jdbc.query("select id,nickname,password_hash,status from users where nickname_key=? and status in ('active','suspended') and role='member'", (row, i) -> new String[]{row.getString("id"),row.getString("nickname"),row.getString("password_hash"),row.getString("status")}, nickname.key());
        if (users.isEmpty() || !hasher.matches(users.get(0)[2], password)) throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "닉네임 또는 비밀번호를 확인해 주세요.");
        // 비밀번호가 맞을 때만 정지 사실을 알린다(남의 계정 정지 여부를 떠보지 못하게).
        if ("suspended".equals(users.get(0)[3])) throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "운영 정책에 따라 이용이 정지된 계정이에요. 문의하기로 알려 주세요.");
        limiter.clear("login-account",nickname.key()); String session=token(), csrf=token();
        jdbc.update("insert into sessions (id,user_id,token_hash,csrf_hash,csrf_token,expires_at) values (?::uuid,?::uuid,?,?,?,now()+interval '30 days')", UUID.randomUUID().toString(),users.get(0)[0],digest(session),digest(csrf),csrf);
        return new SignupResult(users.get(0)[0],users.get(0)[1],null,csrf,session);
    }
    private String token(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private String digest(String s){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
