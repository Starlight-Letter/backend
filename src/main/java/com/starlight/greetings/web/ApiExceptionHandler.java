package com.starlight.greetings.web;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException exception) { return error(exception.status(), exception.code(), exception.getMessage()); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> invalidJson() { return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "JSON 형식이 올바르지 않습니다."); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> invalidData() { return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 정보를 확인해 주세요."); }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound() { return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청하신 리소스를 찾을 수 없습니다."); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected() { return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청을 처리하지 못했습니다."); }
    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) { return ResponseEntity.status(status).body(Map.of("error", Map.of("code", code, "message", message))); }
}
