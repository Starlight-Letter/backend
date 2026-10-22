package com.starlight.greetings.application;

import com.starlight.greetings.web.SignupResult;
import java.util.Map;

public interface SignupService {
    /** clientIp: 같은 곳에서 계정을 마구 만드는 것을 막는 데 쓴다(null이면 IP 제한을 건너뜀). */
    SignupResult signup(String nickname, String password, String clientIp);
    default boolean isNicknameAvailable(String nickname) { return true; }
    /** 가입 화면의 닉네임 확인: {available, reason(invalid·not_allowed·taken), message} */
    default Map<String, Object> checkNickname(String nickname) { return Map.of("available", isNicknameAvailable(nickname)); }
}
