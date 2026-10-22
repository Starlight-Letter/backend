package com.starlight.greetings.application;

import java.util.Map;

public interface AchievementService {
    /** 진행도를 계산하고 새로 조건을 채운 업적을 기록한다. {achievements: [...], newlyUnlocked: [...], completed} */
    Map<String, Object> evaluate(String userId);
    /** 새 편지에 유리병 테마를 쓸 수 있는지: 1·2는 모두, 5는 세 다리의 안부, 6은 북두칠성 완성. 3·4는 더 고를 수 없다 */
    boolean canUseTheme(String userId, int theme);
}
