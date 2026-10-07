package com.starlight.greetings.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.starlight.greetings.application.AccountService;
import com.starlight.greetings.application.SessionService;
import com.starlight.greetings.web.SignupResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountController.class)
class AccountControllerTest {
 @Autowired MockMvc mvc; @MockBean AccountService accounts; @MockBean SessionService sessions;
 @Test void recoversPasswordAndReturnsNewRecoveryCode() throws Exception {
  given(accounts.recover(eq("별빛이"), eq("recovery"), eq("next-password"))).willReturn("next-code");
  mvc.perform(post("/api/v1/auth/recover").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"별빛이\",\"recoveryCode\":\"recovery\",\"newPassword\":\"next-password\"}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.recoveryCode").value("next-code"));
 }
 @Test void withdrawsMemberAndClearsSessionCookie() throws Exception {
  given(sessions.member("session", "csrf")).willReturn(new SessionView("u", "별빛이", "member", "csrf"));
  mvc.perform(post("/api/v1/me/withdraw").cookie(new jakarta.servlet.http.Cookie("star_session_dev", "session")).header("x-csrf-token", "csrf").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"current-password\"}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.withdrawn").value(true));
 }
}
