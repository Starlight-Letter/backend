package com.starlight.greetings.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.starlight.greetings.application.LetterWriteService;
import com.starlight.greetings.application.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LetterWriteController.class)
class LetterWriteControllerTest {
 @Autowired MockMvc mvc; @MockBean LetterWriteService letters; @MockBean SessionService sessions;
 @Test void writesALetterForTheCurrentBridgeQuestion() throws Exception {
  given(letters.write(any())).willReturn(new com.starlight.greetings.application.LetterWriteResult("letter-1", false)); given(sessions.member(any(),any())).willReturn(new SessionView("u","별빛이","member","csrf"));
  mvc.perform(post("/api/v1/bridges/bongnim/letters").cookie(new jakarta.servlet.http.Cookie("star_session_dev","session")).header("x-csrf-token","csrf").header("idempotency-key","12345678").contentType(MediaType.APPLICATION_JSON).content("{\"questionId\":\"00000000-0000-4000-8000-000000000101\",\"body\":\"오늘도 잘 버텼어요.\",\"theme\":2}"))
   .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value("letter-1"));
 }
}
