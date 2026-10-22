package com.starlight.greetings.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.starlight.greetings.application.AchievementService;
import com.starlight.greetings.application.SessionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AchievementController.class)
class AchievementControllerTest {
 @Autowired MockMvc mvc;
 @MockBean AchievementService achievements;
 @MockBean SessionService sessions;
 private final jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie("star_session_dev", "session");

 @Test void readsMyConstellation() throws Exception {
  given(sessions.current("session")).willReturn(new SessionView("user-1", "별빛이", "member", "csrf"));
  given(achievements.evaluate("user-1")).willReturn(Map.of("achievements", List.of(Map.of("code", "first_letter", "unlocked", true)), "completed", false, "newlyUnlocked", List.of("first_letter")));
  mvc.perform(get("/api/v1/me/achievements").cookie(cookie))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.achievements[0].code").value("first_letter"))
    .andExpect(jsonPath("$.data.newlyUnlocked[0]").value("first_letter"));
 }

 @Test void rejectsAdminSession() throws Exception {
  given(sessions.current("session")).willReturn(new SessionView("admin-1", "운영자", "admin", "csrf"));
  mvc.perform(get("/api/v1/me/achievements").cookie(cookie)).andExpect(status().isForbidden());
 }
}
