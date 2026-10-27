package com.starlight.greetings.application;

import com.starlight.greetings.domain.auth.SignupValidator;
import com.starlight.greetings.web.SignupResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.starlight.greetings.web.ApiException;
import com.starlight.greetings.domain.auth.PasswordHasher;

@Service
public class JdbcSignupService implements SignupService {
    private final JdbcTemplate jdbc; private final SignupValidator validator = new SignupValidator();
    private final PasswordHasher passwords = new PasswordHasher(); private final SecureRandom random = new SecureRandom(); private final RequestRateLimiter limiter; private final TransactionTemplate tx; private final NicknamePolicy policy;
    public JdbcSignupService(JdbcTemplate jdbc, RequestRateLimiter limiter, TransactionTemplate tx, NicknamePolicy policy) { this.jdbc = jdbc; this.limiter=limiter; this.tx = tx; this.policy = policy; }
    // 횟수 제한과 비밀번호 해시는 트랜잭션 밖에서 먼저 끝낸다. 제한(REQUIRES_NEW)을 트랜잭션 안에서 부르면 요청마다 연결을 2개 잡아
    // 동시 가입이 연결 풀 크기를 넘는 순간 서로 기다리며 멈춘다. 계정과 세션 저장만 짧게 묶는다.
    @Override public SignupResult signup(String nicknameInput, String passwordInput, String clientIp) {
        SignupValidator.Nickname nickname; String password;
        try { nickname = validator.nickname(nicknameInput); password = validator.password(passwordInput); }
        catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SIGNUP", e.getMessage()); }
        // 닉네임 내용 검사는 메모리에서 끝난다(검사 규칙은 ModerationRules가 캐시). 해시·DB 작업 전에 먼저 거른다
        policy.require(nickname.display());
        limiter.consume("signup-account",nickname.key(),5);
        // 닉네임만 바꿔 가며 계정을 계속 만드는 것을 막는다: 같은 IP에서 1시간에 5개까지
        if (clientIp != null && !clientIp.isBlank()) limiter.consume("signup-ip",clientIp,5,60);
        String id = UUID.randomUUID().toString(), recovery = token(), session = token(), csrf = token();
        String passwordHash = passwords.hash(password), recoveryHash = passwords.hash(recovery);
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("insert into users (id,nickname,nickname_key,password_hash,recovery_code_hash,role,status) values (?::uuid,?,?,?,?, 'member','active')", id, nickname.display(), nickname.key(), passwordHash, recoveryHash);
                jdbc.update("insert into sessions (id,user_id,token_hash,csrf_hash,csrf_token,expires_at) values (?::uuid,?::uuid,?,?,?,now()+interval '30 days')", UUID.randomUUID().toString(), id, digest(session), digest(csrf), csrf);
            });
        } catch (DuplicateKeyException error) { throw new ApiException(HttpStatus.CONFLICT, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다."); }
        return new SignupResult(id, nickname.display(), recovery, csrf, session);
    }
    @Override public boolean isNicknameAvailable(String nicknameInput) { return Boolean.TRUE.equals(checkNickname(nicknameInput).get("available")); }
    // 가입 화면 '중복 확인': 형식 → 내용(사칭·욕설·연락처·광고·검사 규칙) → 중복 순으로 보고, 왜 안 되는지 알려준다
    @Override public Map<String, Object> checkNickname(String nicknameInput) {
        SignupValidator.Nickname nickname;
        try { nickname = validator.nickname(nicknameInput); }
        catch (IllegalArgumentException e) { return Map.of("available", false, "reason", "invalid", "message", e.getMessage()); }
        String problem = policy.explain(nickname.display());
        if (problem != null) return Map.of("available", false, "reason", "not_allowed", "message", problem);
        Integer count = jdbc.queryForObject("select count(*) from users where nickname_key = ? and status in ('active', 'suspended')", Integer.class, nickname.key());
        if (count != null && count > 0) return Map.of("available", false, "reason", "taken", "message", "이미 다른 회원이 사용 중인 닉네임입니다.");
        return Map.of("available", true);
    }
    private String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String digest(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
