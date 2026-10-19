package com.starlight.greetings.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiRequestContextFilter extends OncePerRequestFilter {
    private final Set<String> origins;

    public ApiRequestContextFilter(
            @Value("${app.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174,http://localhost:5175,http://127.0.0.1:5175}") String values
    ) {
        // 따옴표나 공백이 섞여 들어와도 안전하게 정제
        this.origins = Arrays.stream(values.replace("\"", "").split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("x-request-id", UUID.randomUUID().toString());

        // 1. Content-Length가 양수일 때만 16KB 초과 검사 (chunked인 -1 방어)
        long length = req.getContentLengthLong();
        if (length > 0 && length > 16 * 1024) {
            send(res, 413, "PAYLOAD_TOO_LARGE", "요청 본문이 너무 큽니다.");
            return;
        }

        // 2. Origin 검증 (GET, HEAD, OPTIONS 제외)
        String origin = req.getHeader("Origin");
        if (!"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod()) && !"OPTIONS".equals(req.getMethod())
                && origin != null) {

            // 끝에 슬래시가 붙어오는 경우 대비
            String normalizedOrigin = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;

            if (!origins.contains(normalizedOrigin)) {
                send(res, 403, "ORIGIN_DENIED", "요청 출처를 확인해 주세요. (" + origin + ")");
                return;
            }
        }

        chain.doFilter(req, res);
    }

    private void send(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
        res.getWriter().flush(); // 버퍼 강제 전송
    }
}
