package com.starlight.greetings.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.starlight.greetings.application.BridgeQueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BridgeController.class)
@Import(ApiExceptionHandler.class)
class BridgeControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private BridgeQueryService bridgeQueryService;

    @Test
    void listsBridgesUsingTheEstablishedApiEnvelope() throws Exception {
        given(bridgeQueryService.list()).willReturn(List.of(new BridgeView(
            "bongnim", "봉림교", 1,
            new BridgeQuestion("question-1", "오늘 건네고 싶은 말은 무엇인가요?", Instant.parse("2026-10-04T15:00:00Z"))
        )));

        mvc.perform(get("/api/v1/bridges"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("bongnim"))
            .andExpect(jsonPath("$.data[0].currentQuestion.body").value("오늘 건네고 싶은 말은 무엇인가요?"));
    }

    @Test
    void returnsOneBridgeOrTheEstablishedNotFoundError() throws Exception {
        given(bridgeQueryService.get("bongnim")).willReturn(new BridgeView("bongnim", "봉림교", 1, null));

        mvc.perform(get("/api/v1/bridges/bongnim"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("봉림교"));

        mvc.perform(get("/api/v1/bridges/unknown"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("BRIDGE_NOT_FOUND"));
    }
}
