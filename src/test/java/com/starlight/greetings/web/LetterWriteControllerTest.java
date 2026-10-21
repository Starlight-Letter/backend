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
@org.springframework.context.annotation.Import(ApiExceptionHandler.class)
class LetterWriteControllerTest {
 @Autowired MockMvc mvc; @MockBean LetterWriteService letters; @MockBean SessionService sessions; @MockBean com.starlight.greetings.application.BridgePassService passes;
 @Test void refusesALetterWithoutTheBridgeQrPass() throws Exception {
  given(sessions.member(any(),any())).willReturn(new SessionView("u","별빛이","member","csrf"));
  org.mockito.BDDMockito.willThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"BRIDGE_LOCKED","이 다리의 편지는 다리 현장의 QR로 들어와야 쓸 수 있어요.")).given(passes).requireWrite(null,"bongnim");
  mvc.perform(post("/api/v1/bridges/bongnim/letters").cookie(new jakarta.servlet.http.Cookie("star_session_dev","session")).header("x-csrf-token","csrf").header("idempotency-key","12345678").contentType(MediaType.APPLICATION_JSON).content("{\"questionId\":\"00000000-0000-4000-8000-000000000101\",\"body\":\"오늘도 잘 버텼어요.\",\"theme\":2}"))
   .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BRIDGE_LOCKED"));
  org.mockito.Mockito.verify(letters,org.mockito.Mockito.never()).write(any());
 }
 @Test void writesALetterForTheCurrentBridgeQuestion() throws Exception {
  given(letters.write(any())).willReturn(new com.starlight.greetings.application.LetterWriteResult("letter-1", false)); given(sessions.member(any(),any())).willReturn(new SessionView("u","별빛이","member","csrf"));
  mvc.perform(post("/api/v1/bridges/bongnim/letters").cookie(new jakarta.servlet.http.Cookie("star_session_dev","session")).header("x-csrf-token","csrf").header("idempotency-key","12345678").contentType(MediaType.APPLICATION_JSON).content("{\"questionId\":\"00000000-0000-4000-8000-000000000101\",\"body\":\"오늘도 잘 버텼어요.\",\"theme\":2}"))
   .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value("letter-1"));
 }
 @Test void tellsAMemberHowManyLettersAreLeftToday() throws Exception {
  given(sessions.current(any())).willReturn(new SessionView("u","별빛이","member","csrf"));
  given(letters.quota("u","bongnim")).willReturn(java.util.Map.of("remaining",2,"bridgeRemaining",2,"totalRemaining",8));
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/bridges/bongnim/letters/quota").cookie(new jakarta.servlet.http.Cookie("star_session_dev","session")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.remaining").value(2)).andExpect(jsonPath("$.data.totalRemaining").value(8));
 }
}
