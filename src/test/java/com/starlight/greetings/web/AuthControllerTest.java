package com.starlight.greetings.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.starlight.greetings.application.SignupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
class AuthControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private SignupService signupService;

    @Test void signsUpAndIssuesTheDevelopmentSessionCookie() throws Exception {
        given(signupService.signup(any(), any())).willReturn(new SignupResult("user-1", "별빛이", "recovery", "csrf", "session"));
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"별빛이\",\"password\":\"password1\"}"))
            .andExpect(status().isCreated())
            .andExpect(cookie().value("star_session_dev", "session"))
            .andExpect(jsonPath("$.data.user.nickname").value("별빛이"))
            .andExpect(jsonPath("$.data.csrfToken").value("csrf"));
    }
}
