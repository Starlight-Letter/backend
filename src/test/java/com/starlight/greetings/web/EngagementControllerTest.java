package com.starlight.greetings.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.starlight.greetings.application.EngagementService;
import com.starlight.greetings.application.SessionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EngagementController.class)
class EngagementControllerTest {
 @Autowired MockMvc mvc;
 @MockBean EngagementService engagement;
 @MockBean SessionService sessions;
 private final jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie("star_session_dev", "session");

 @Test void togglesReactionForAuthenticatedMember() throws Exception {
  given(sessions.member("session", "csrf")).willReturn(new SessionView("user-1", "별빛이", "member", "csrf"));
  given(engagement.react(eq("letter-1"), eq("user-1"), eq(true))).willReturn(Map.of("active", true, "reactionCount", 3));
  mvc.perform(put("/api/v1/letters/letter-1/reaction").cookie(cookie).header("x-csrf-token", "csrf"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true)).andExpect(jsonPath("$.data.reactionCount").value(3));
 }

 @Test void readsPersonalSavedLetters() throws Exception {
  given(sessions.current("session")).willReturn(new SessionView("user-1", "별빛이", "member", "csrf"));
  given(engagement.saved("user-1")).willReturn(List.of(Map.of("id", "letter-1", "unavailable", false, "preview", "오늘도 수고했어요.")));
  mvc.perform(get("/api/v1/me/saved").cookie(cookie))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("letter-1"));
 }

 @Test void servesWeeklyBestAcrossAllBridges() throws Exception {
  given(engagement.best("all", null, "week")).willReturn(List.of(Map.of("rank", 1, "id", "letter-1", "reactionCount", 5)));
  mvc.perform(get("/api/v1/best").queryParam("scope", "all").queryParam("period", "week"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].rank").value(1));
 }
}
