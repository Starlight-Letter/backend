package com.starlight.greetings.web;

import com.starlight.greetings.application.AchievementService;
import com.starlight.greetings.application.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/achievements")
public class AchievementController {
    private final AchievementService achievements;
    private final SessionService sessions;
    public AchievementController(AchievementService achievements, SessionService sessions) { this.achievements = achievements; this.sessions = sessions; }

    // 열 때마다 진행도를 다시 세고, 새로 채운 업적을 기록해 newlyUnlocked로 알려준다
    @GetMapping public ApiEnvelope<Map<String, Object>> mine(HttpServletRequest request) {
        SessionView user = sessions.current(SessionCookie.token(request));
        if (!"member".equals(user.role())) throw new ApiException(HttpStatus.FORBIDDEN, "MEMBER_REQUIRED", "회원 기능을 이용할 수 없습니다.");
        return ApiEnvelope.of(achievements.evaluate(user.id()));
    }
}
