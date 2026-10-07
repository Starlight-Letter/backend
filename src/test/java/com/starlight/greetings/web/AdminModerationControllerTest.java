package com.starlight.greetings.web;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.starlight.greetings.application.AdminModerationService;
import com.starlight.greetings.application.SessionService;
import java.util.List; import java.util.Map;
import org.junit.jupiter.api.Test; import org.springframework.beans.factory.annotation.Autowired; import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest; import org.springframework.boot.test.mock.mockito.MockBean; import org.springframework.http.MediaType; import org.springframework.test.web.servlet.MockMvc;
@WebMvcTest(AdminModerationController.class) class AdminModerationControllerTest {
 @Autowired MockMvc mvc; @MockBean AdminModerationService moderation; @MockBean SessionService sessions;
 @Test void listsReportsForAnAuthorizedAdministrator() throws Exception { given(sessions.admin("session","csrf")).willReturn(new SessionView("admin","운영자","admin","csrf")); given(moderation.reports()).willReturn(List.of(Map.of("letterId","letter-1","reportCount",5))); mvc.perform(get("/api/v1/admin/reports").cookie(new jakarta.servlet.http.Cookie("star_session_dev","session")).header("x-csrf-token","csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].reportCount").value(5)); }
 @Test void deletesLetterAfterAdminReview() throws Exception { given(sessions.admin("session","csrf")).willReturn(new SessionView("admin","운영자","admin","csrf")); given(moderation.review(eq("letter-1"),eq("delete"),eq("개인정보 노출"),eq("admin"))).willReturn(Map.of("id","letter-1","status","deleted")); mvc.perform(post("/api/v1/admin/letters/letter-1/review").cookie(new jakarta.servlet.http.Cookie("star_session_dev","session")).header("x-csrf-token","csrf").contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"delete\",\"reason\":\"개인정보 노출\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("deleted")); }
}
