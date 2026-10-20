package com.starlight.greetings.application;

import com.starlight.greetings.domain.auth.SignupValidator;
import com.starlight.greetings.web.SignupResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.starlight.greetings.web.ApiException;
import com.starlight.greetings.domain.auth.PasswordHasher;

@Service
public class JdbcSignupService implements SignupService {
    private final JdbcTemplate jdbc; private final SignupValidator validator = new SignupValidator();
    private final PasswordHasher passwords = new PasswordHasher(); private final SecureRandom random = new SecureRandom(); private final RequestRateLimiter limiter;
    public JdbcSignupService(JdbcTemplate jdbc, RequestRateLimiter limiter) { this.jdbc = jdbc; this.limiter=limiter; }
    @Override @Transactional public SignupResult signup(String nicknameInput, String passwordInput, String clientIp) {
        var nickname = validator.nickname(nicknameInput); String password = validator.password(passwordInput); limiter.consume("signup-account",nickname.key(),5);
        // 닉네임만 바꿔 가며 계정을 계속 만드는 것을 막는다: 같은 IP에서 1시간에 5개까지
        if (clientIp != null && !clientIp.isBlank()) limiter.consume("signup-ip",clientIp,5,60);
        String id = UUID.randomUUID().toString(), recovery = token(), session = token(), csrf = token();
        try {
            jdbc.update("insert into users (id,nickname,nickname_key,password_hash,recovery_code_hash,role,status) values (?::uuid,?,?,?,?, 'member','active')", id, nickname.display(), nickname.key(), passwords.hash(password), passwords.hash(recovery));
            jdbc.update("insert into sessions (id,user_id,token_hash,csrf_hash,csrf_token,expires_at) values (?::uuid,?::uuid,?,?,?,now()+interval '30 days')", UUID.randomUUID().toString(), id, digest(session), digest(csrf), csrf);
        } catch (DuplicateKeyException error) { throw new ApiException(HttpStatus.CONFLICT, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다."); }
        return new SignupResult(id, nickname.display(), recovery, csrf, session);
    }
    @Override public boolean isNicknameAvailable(String nicknameInput) {
        try {
            var nickname = validator.nickname(nicknameInput);
            Integer count = jdbc.queryForObject("select count(*) from users where nickname_key = ? and status in ('active', 'suspended')", Integer.class, nickname.key());
            return count != null && count == 0;
        } catch (Exception e) {
            return false;
        }
    }
    private String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String digest(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
