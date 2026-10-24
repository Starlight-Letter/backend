package com.starlight.greetings.web;

import com.starlight.greetings.application.LetterQueryService;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bridges/{bridgeId}/river")
public class RiverLetterController {
    private final LetterQueryService letterQueryService;
    public RiverLetterController(LetterQueryService letterQueryService) { this.letterQueryService = letterQueryService; }
    // seen: 이 기기에서 이미 본 편지 ID(쉼표 구분). 강물에서 빼지 않고 덜 뽑히게만 한다
    @GetMapping public ApiEnvelope<List<LetterPreview>> river(@PathVariable String bridgeId, @RequestParam(required = false) String seen) {
        List<String> ids = seen == null || seen.isBlank() ? List.of() : Arrays.asList(seen.split(",", 200));
        return ApiEnvelope.of(letterQueryService.river(bridgeId, ids));
    }
}
