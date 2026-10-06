package com.starlight.greetings.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.starlight.greetings.application.LetterQueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RiverLetterController.class)
class RiverLetterControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private LetterQueryService letterQueryService;

    @Test void returnsBridgeRiverLetters() throws Exception {
        given(letterQueryService.river("bongnim")).willReturn(List.of(new LetterPreview("letter-1", "bongnim", 2, true, "작은 안부", Instant.parse("2026-10-01T00:00:00Z"))));
        mvc.perform(get("/api/v1/bridges/bongnim/river"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].preview").value("작은 안부"));
    }
}
