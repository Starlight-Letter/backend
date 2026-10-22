package com.starlight.greetings.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// 업적(별자리): 북두칠성 별 7개. 진행도는 지금 데이터로 세고, 조건을 처음 채운 순간 user_achievements에 기록한다.
// 기록은 지우지 않으므로 나중에 위로가 취소되거나 편지를 지워도 켜진 별은 꺼지지 않는다.
// 지우고 다시 쓰면 채워지는 "편지 N통" 같은 업적은 두지 않는다(직접 삭제하면 하루 작성 수가 돌아오기 때문).
@Service
public class JdbcAchievementService implements AchievementService {
    static final String COMPLETE = "constellation";
    // 북두칠성 순서대로. 각 업적이 볼 진행도 열과 목표
    private record Goal(String code, String column, int target) {}
    private static final List<Goal> GOALS = List.of(
        new Goal("first_letter", "letters", 1),
        new Goal("two_nights", "nights", 2),
        new Goal("three_bridges", "bridges", 3),
        new Goal("first_comfort", "comfort_received", 1),
        new Goal("kept_once", "kept_by_others", 1),
        new Goal("kind_reader", "comfort_given", 3),
        new Goal("collector", "kept_others", 3));
    private final JdbcTemplate jdbc;

    public JdbcAchievementService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Map<String, Object> evaluate(String userId) {
        // 받은 위로·보관 수는 띄워 보낸 흔적(letterTrace)과 같은 기준: 내가 내 편지에 남긴 것은 세지 않는다
        Map<String, Object> progress = jdbc.queryForMap("""
            SELECT (SELECT count(*)::int FROM letters WHERE author_user_id = ?::uuid AND status <> 'deleted') AS letters,
                   (SELECT count(DISTINCT (created_at AT TIME ZONE 'Asia/Seoul')::date)::int FROM letters
                     WHERE author_user_id = ?::uuid AND status <> 'deleted') AS nights,
                   (SELECT count(DISTINCT bridge_id)::int FROM letters WHERE author_user_id = ?::uuid AND status <> 'deleted') AS bridges,
                   (SELECT count(*)::int FROM reactions r JOIN letters l ON l.id = r.letter_id
                     WHERE l.author_user_id = ?::uuid AND l.status <> 'deleted' AND r.active = true AND r.user_id IS DISTINCT FROM ?::uuid) AS comfort_received,
                   (SELECT count(*)::int FROM saves s JOIN letters l ON l.id = s.letter_id
                     WHERE l.author_user_id = ?::uuid AND l.status <> 'deleted' AND s.user_id <> ?::uuid) AS kept_by_others,
                   (SELECT count(*)::int FROM reactions r JOIN letters l ON l.id = r.letter_id
                     WHERE r.user_id = ?::uuid AND r.active = true AND l.status <> 'deleted' AND l.author_user_id IS DISTINCT FROM ?::uuid) AS comfort_given,
                   (SELECT count(*)::int FROM saves s JOIN letters l ON l.id = s.letter_id
                     WHERE s.user_id = ?::uuid AND l.status <> 'deleted' AND l.author_user_id IS DISTINCT FROM ?::uuid) AS kept_others
            """, userId, userId, userId, userId, userId, userId, userId, userId, userId, userId, userId);
        List<String> newlyUnlocked = new ArrayList<>();
        for (Goal goal : GOALS) if (((Number) progress.get(goal.column())).intValue() >= goal.target() && unlock(userId, goal.code())) newlyUnlocked.add(goal.code());
        Map<String, String> unlocked = unlocked(userId);
        if (GOALS.stream().allMatch(goal -> unlocked.containsKey(goal.code())) && unlock(userId, COMPLETE)) { newlyUnlocked.add(COMPLETE); unlocked.putAll(unlocked(userId)); }

        List<Map<String, Object>> achievements = new ArrayList<>();
        for (Goal goal : GOALS) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", goal.code()); item.put("unlocked", unlocked.containsKey(goal.code())); item.put("unlockedAt", unlocked.get(goal.code()));
            item.put("progress", Math.min(((Number) progress.get(goal.column())).intValue(), goal.target())); item.put("target", goal.target());
            achievements.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("achievements", achievements); result.put("completed", unlocked.containsKey(COMPLETE)); result.put("newlyUnlocked", newlyUnlocked);
        return result;
    }

    // 새 편지에 고를 수 있는 병은 기본 2종(1 달빛, 2 별빛)과 별자리로 여는 2종(5, 6). 3·4는 예전 편지 표시에만 남아 있다
    @Override public boolean canUseTheme(String userId, int theme) {
        if (theme == 1 || theme == 2) return true;
        String code = theme == 5 ? "three_bridges" : theme == 6 ? COMPLETE : null;
        if (code == null) return false;
        if (unlocked(userId).containsKey(code)) return true;
        evaluate(userId); // 조건은 채웠지만 아직 계정·쓰기 화면을 열지 않아 기록되지 않은 경우
        return unlocked(userId).containsKey(code);
    }

    // 동시에 두 요청이 같은 업적을 기록해도 한 번만 들어가고, 처음 기록한 쪽만 '새로 켜짐'으로 본다
    private boolean unlock(String userId, String code) {
        return jdbc.update("INSERT INTO user_achievements (user_id, code) VALUES (?::uuid, ?) ON CONFLICT (user_id, code) DO NOTHING", userId, code) == 1;
    }

    private Map<String, String> unlocked(String userId) {
        Map<String, String> rows = new HashMap<>();
        jdbc.query("SELECT code, unlocked_at FROM user_achievements WHERE user_id = ?::uuid",
            rs -> { rows.put(rs.getString(1), rs.getTimestamp(2).toInstant().toString()); }, userId);
        return rows;
    }
}
