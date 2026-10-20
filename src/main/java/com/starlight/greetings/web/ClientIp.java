package com.starlight.greetings.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청한 사람의 IP를 고른다. 프록시(로드밸런서·CDN) 뒤에서 운영할 때는 app.trusted-proxy-hops에
 * 앞단 프록시 수를 넣는다. 프록시는 X-Forwarded-For 끝에 직전 주소를 덧붙이므로, 뒤에서부터
 * 믿을 수 있는 프록시 수만큼 건너뛴 값이 실제 접속자다. 0이면 헤더를 믿지 않고 직접 연결 주소를 쓴다
 * (헤더는 누구나 꾸밀 수 있으므로 프록시가 없는데 믿으면 제한을 피해 갈 수 있다).
 */
public final class ClientIp {
    private ClientIp() {}

    public static String of(HttpServletRequest request, int trustedProxyHops) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (trustedProxyHops > 0 && forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            int index = hops.length - trustedProxyHops;
            if (index >= 0) return hops[index].trim();
            return hops[0].trim();
        }
        return request.getRemoteAddr();
    }
}
