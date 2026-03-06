package com.tradebot.demo.controller;

import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.AiDiagnosticsRequestDTO;
import com.tradebot.dto.AiDiagnosticsResponseDTO;
import com.tradebot.service.AiDiagnosticsService;
import com.tradebot.trace.TraceIdContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo-trading/ai")
@RequiredArgsConstructor
public class DemoAiDiagnosticsController {

    private final AiDiagnosticsService aiDiagnosticsService;
    private final AiRoutingResolver aiRoutingResolver;
    private final ControlCenterSettingsProvider controlCenterSettingsProvider;

    @PostMapping("/diagnostics")
    public ResponseEntity<AiDiagnosticsResponseDTO> diagnostics(
            @Valid @RequestBody AiDiagnosticsRequestDTO request,
            HttpServletRequest httpRequest) {
        if (!isVisionEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(AiDiagnosticsResponseDTO.featureDisabled());
        }
        if (!controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading().isEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(AiDiagnosticsResponseDTO.demoDisabled());
        }

        String traceId = TraceIdContext.resolveOrCreate(httpRequest);
        AiDiagnosticsResponseDTO response = aiDiagnosticsService.diagnose(AiMode.DEMO, request, traceId);
        return ResponseEntity.ok(response);
    }

    private boolean isVisionEnabled() {
        return aiRoutingResolver.resolveTaskRoute(AiTaskType.VISION_DIAGNOSTIC, AiMode.DEMO).isEnabled();
    }
}
