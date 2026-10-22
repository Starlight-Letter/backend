package com.starlight.greetings.application;

import java.util.List;
import java.util.Map;

public interface EngagementService {
    Map<String, Object> state(String letterId, String userId);
    Map<String, Object> react(String letterId, String userId, boolean active);
    Map<String, Object> save(String letterId, String userId, boolean saved);
    List<Map<String, Object>> saved(String userId);
    Map<String, Object> letterTrace(String userId);
    List<Map<String, Object>> myLetters(String userId);
    Map<String, Object> deleteMyLetter(String letterId, String userId);
    List<Map<String, Object>> best(String scope, String bridgeId, String period);
}
