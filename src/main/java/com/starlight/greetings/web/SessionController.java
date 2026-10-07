package com.starlight.greetings.web;
import com.starlight.greetings.application.SessionService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping; import org.springframework.web.bind.annotation.RestController; import jakarta.servlet.http.HttpServletRequest;
@RestController public class SessionController { private final SessionService sessions; public SessionController(SessionService sessions){this.sessions=sessions;} @GetMapping("/api/v1/me") public ApiEnvelope<Map<String,Object>> me(HttpServletRequest request){ String token=null; if(request.getCookies()!=null) for(var cookie:request.getCookies()) if("star_session_dev".equals(cookie.getName())) token=cookie.getValue(); SessionView view=sessions.current(token); return ApiEnvelope.of(Map.of("user",Map.of("id",view.id(),"nickname",view.nickname(),"role",view.role()),"csrfToken",view.csrfToken()));}}
