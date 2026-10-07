package com.starlight.greetings.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.starlight.greetings.application.InquiryService;
import com.starlight.greetings.application.ReportService;
import com.starlight.greetings.application.SessionService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({ReportController.class, InquiryController.class})
class ModerationControllerTest {
 @Autowired MockMvc mvc;
 @MockBean ReportService reports; @MockBean InquiryService inquiries; @MockBean SessionService sessions;
 @Test void reportsWithAMemberSession() throws Exception {
  given(sessions.member("session", "csrf")).willReturn(new SessionView("user-1", "별빛이", "member", "csrf"));
  given(reports.report(eq("letter-1"), eq("user-1"), eq("personal_info"))).willReturn(Map.of("hidden", false, "reportCount", 1));
  mvc.perform(post("/api/v1/letters/letter-1/reports").cookie(new jakarta.servlet.http.Cookie("star_session_dev", "session")).header("x-csrf-token", "csrf").contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"personal_info\"}"))
    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.reportCount").value(1));
 }
 @Test void acceptsAnonymousServiceInquiry() throws Exception {
  given(inquiries.submit(any(), any())).willReturn(Map.of("id", "inquiry-1", "status", "open"));
  mvc.perform(post("/api/v1/inquiries").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"service\",\"body\":\"이용 중 문제가 있어요.\"}"))
    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("open"));
 }
}
