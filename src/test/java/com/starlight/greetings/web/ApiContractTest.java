package com.starlight.greetings.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.starlight.greetings.StarlightApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = HealthController.class)
@Import({ApiRequestContextFilter.class, ApiExceptionHandler.class, WebConfiguration.class})
class ApiContractTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void healthUsesTheEstablishedEnvelopeAndHeaders() throws Exception {
        mvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ok"))
            .andExpect(header().string("cache-control", "no-store"))
            .andExpect(header().exists("x-request-id"));
    }

    @Test
    void allowsCredentialedPreflightFromTheFrontendOrigin() throws Exception {
        mvc.perform(options("/api/v1/bridges")
                .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5175")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:5175"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }
}
