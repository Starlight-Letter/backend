package com.starlight.greetings.application;
import java.util.Map;
public interface SurveyMetricsService { Map<String,Object> survey(); Map<String,Object> event(Map<String,Object> input,String source); Map<String,Object> surveyOpen(Map<String,Object> input,String source); }
