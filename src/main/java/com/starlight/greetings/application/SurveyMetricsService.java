package com.starlight.greetings.application;
import java.util.Map;
public interface SurveyMetricsService { Map<String,Object> survey(); /** viewerKind: guest·member·null(운영자 등). 서버가 세션으로 판단한다. */ Map<String,Object> event(Map<String,Object> input,String viewerKind); Map<String,Object> surveyOpen(Map<String,Object> input); Map<String,Object> settings(); Map<String,Object> configure(Map<String,Object> input,String actorId); Map<String,Object> summary(String from,String to); }
