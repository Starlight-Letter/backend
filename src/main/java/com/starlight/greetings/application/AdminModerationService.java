package com.starlight.greetings.application;
import java.util.List; import java.util.Map;
public interface AdminModerationService { List<Map<String,Object>> reports(); Map<String,Object> review(String letterId,String decision,String reason,String actorId); }
