package com.starlight.greetings.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.starlight.greetings.application.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SessionController.class)
class SessionControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private SessionService sessionService;
    @Test void readsTheMemberSessionFromTheDevelopmentCookie() throws Exception {
        given(sessionService.current("session")).willReturn(new SessionView("user-1", "별빛이", "member", "csrf"));
        mvc.perform(get("/api/v1/me").cookie(new jakarta.servlet.http.Cookie("star_session_dev", "session")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.nickname").value("별빛이"));
    }
}
