package com.tradebot.demo.controller;

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
@RequestMapping("/api/v1/demo-trading/ai/models")
@RequiredArgsConstructor
public class DemoAiModelController {

    private final AiModelSettingsService aiModelSettingsService;
    private final LocalMutationGuard localMutationGuard;

    @GetMapping
    public ResponseEntity<AiModelsResponseDTO> getDemoModels() {
        return ResponseEntity.ok(aiModelSettingsService.getDemoSettings());
    }

    @PostMapping
    @RequiresPermission("ai.models.update")
    public ResponseEntity<AiModelsResponseDTO> updateDemoModels(
            @RequestBody AiModelsUpdateRequestDTO request,
            HttpServletRequest httpServletRequest) {
        localMutationGuard.assertLocal(httpServletRequest);
        return ResponseEntity.ok(aiModelSettingsService.updateDemoSettings(request));
    }

    @PostMapping("/test")
    public ResponseEntity<AiModelsTestResponseDTO> testDemoModels(@RequestBody(required = false) AiModelsTestRequestDTO request) {
        AiModelsTestRequestDTO payload = request == null ? new AiModelsTestRequestDTO() : request;
        return ResponseEntity.ok(aiModelSettingsService.simulateTest(AiMode.DEMO, payload));
    }
}
