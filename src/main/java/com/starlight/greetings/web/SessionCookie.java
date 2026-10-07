package com.starlight.greetings.web;
import jakarta.servlet.http.HttpServletRequest;
final class SessionCookie {
 private SessionCookie() {}
 static String token(HttpServletRequest request) { if (request.getCookies()!=null) for (var cookie:request.getCookies()) if ("star_session_dev".equals(cookie.getName())) return cookie.getValue(); return null; }
}
