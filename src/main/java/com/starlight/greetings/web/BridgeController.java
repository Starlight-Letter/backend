package com.starlight.greetings.web;

import com.starlight.greetings.application.BridgeQueryService;
import java.util.List;
import org.springframework.http.HttpStatus;
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

    @GetMapping("/{bridgeId}")
    public ApiEnvelope<BridgeView> get(@org.springframework.web.bind.annotation.PathVariable String bridgeId) {
        BridgeView bridge = bridgeQueryService.get(bridgeId);
        if (bridge == null) throw new ApiException(HttpStatus.NOT_FOUND, "BRIDGE_NOT_FOUND", "다리를 찾을 수 없습니다.");
        return ApiEnvelope.of(bridge);
    }
}
