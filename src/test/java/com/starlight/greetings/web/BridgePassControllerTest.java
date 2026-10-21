package com.starlight.greetings.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.starlight.greetings.application.BridgePassService;
import com.starlight.greetings.application.SessionService;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BridgePassController.class)
@Import(ApiExceptionHandler.class)
class BridgePassControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private BridgePassService passes;
    @MockBean private SessionService sessions;

    @Test
    void opensWritingForTheScannedBridgeInAShortLivedCookie() throws Exception {
        given(passes.issue("sillim", "qr-key", "old-pass")).willReturn(Map.of("token", "new-pass", "bridgeId", "sillim", "expiresAt", "2026-10-21T12:00:00Z"));

        mvc.perform(post("/api/v1/bridges/sillim/pass").cookie(new Cookie("star_bridge_pass", "old-pass")).contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"qr-key\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bridgeId").value("sillim"))
            .andExpect(jsonPath("$.data.token").doesNotExist())
            .andExpect(header().string("Set-Cookie", allOf(containsString("star_bridge_pass=new-pass"), containsString("HttpOnly"), containsString("Max-Age=10800"))));
    }

    @Test
    void rejectsAWrongQrKey() throws Exception {
        given(passes.issue(any(), any(), any())).willThrow(new ApiException(HttpStatus.FORBIDDEN, "QR_INVALID", "이 QR로는 편지 쓰기를 열 수 없어요."));

        mvc.perform(post("/api/v1/bridges/sillim/pass").contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"guess\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("QR_INVALID"));
    }

    @Test
    void reportsNoPassForABrowserThatDidNotScan() throws Exception {
        given(passes.current(null)).willReturn(null);

        mvc.perform(get("/api/v1/bridge-pass"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").doesNotExist());
    }
}
