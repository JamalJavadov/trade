package com.tradebot.controller;

import com.tradebot.ai.AiMode;
import com.tradebot.dto.AiModelsResponseDTO;
import com.tradebot.dto.AiModelsTestRequestDTO;
import com.tradebot.dto.AiModelsTestResponseDTO;
import com.tradebot.dto.AiModelsUpdateRequestDTO;
import com.tradebot.operator.RequiresPermission;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.AiModelSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai/models")
@RequiredArgsConstructor
public class AiModelController {

    private final AiModelSettingsService aiModelSettingsService;
    private final LocalMutationGuard localMutationGuard;

    @GetMapping
    public ResponseEntity<AiModelsResponseDTO> getLiveModels() {
        return ResponseEntity.ok(aiModelSettingsService.getLiveSettings());
    }

    @PostMapping
    @RequiresPermission("ai.models.update")
    public ResponseEntity<AiModelsResponseDTO> updateLiveModels(
            @RequestBody AiModelsUpdateRequestDTO request,
            HttpServletRequest httpServletRequest) {
        localMutationGuard.assertLocal(httpServletRequest);
        return ResponseEntity.ok(aiModelSettingsService.updateLiveSettings(request));
    }

    @PostMapping("/test")
    @RequiresPermission("ai.models.update")
    public ResponseEntity<AiModelsTestResponseDTO> testLiveModels(@RequestBody(required = false) AiModelsTestRequestDTO request) {
        AiModelsTestRequestDTO payload = request == null ? new AiModelsTestRequestDTO() : request;
        return ResponseEntity.ok(aiModelSettingsService.simulateTest(AiMode.LIVE, payload));
    }
}
