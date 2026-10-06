package com.starlight.greetings.web;

import com.starlight.greetings.application.LetterQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bridges/{bridgeId}/river")
public class RiverLetterController {
    private final LetterQueryService letterQueryService;
    public RiverLetterController(LetterQueryService letterQueryService) { this.letterQueryService = letterQueryService; }
    @GetMapping public ApiEnvelope<List<LetterPreview>> river(@PathVariable String bridgeId) { return ApiEnvelope.of(letterQueryService.river(bridgeId)); }
}
