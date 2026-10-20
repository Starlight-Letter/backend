package com.starlight.greetings.application;
import java.util.Map;
public interface LetterWriteService { LetterWriteResult write(Map<String,Object> input); /** 지난 24시간 기준으로 이 다리·전체에 더 쓸 수 있는 편지 수 */ Map<String,Object> quota(String userId,String bridgeId); }
