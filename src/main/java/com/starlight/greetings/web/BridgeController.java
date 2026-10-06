package com.starlight.greetings.web;

import com.starlight.greetings.application.BridgeQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bridges")
public class BridgeController {
    private final BridgeQueryService bridgeQueryService;

    public BridgeController(BridgeQueryService bridgeQueryService) {
        this.bridgeQueryService = bridgeQueryService;
    }

    @GetMapping
    public ApiEnvelope<List<BridgeView>> list() {
        return ApiEnvelope.of(bridgeQueryService.list());
    }
}
