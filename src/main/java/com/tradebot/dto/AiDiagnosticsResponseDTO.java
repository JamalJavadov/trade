package com.tradebot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiDiagnosticsResponseDTO {

    private String summary;
    private String probableRootCause;
    private List<CheckItemDTO> checks = new ArrayList<>();
    private List<FixItemDTO> fixes = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CheckItemDTO {
        private String step;
        private String why;
        private String expected;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FixItemDTO {
        private String fix;
        private String risk;
    }

    public static AiDiagnosticsResponseDTO aiUnavailable() {
        List<CheckItemDTO> checks = List.of(
                new CheckItemDTO(
                        "Verify network and API provider reachability",
                        "Diagnostics relies on an external AI provider",
                        "Provider endpoint is reachable and responsive"),
                new CheckItemDTO(
                        "Retry with a smaller log/context payload",
                        "Large payloads can cause request rejection or timeout",
                        "Request succeeds with compact input"));
        List<FixItemDTO> fixes = List.of(
                new FixItemDTO("Retry diagnostics in 1-2 minutes", "low"),
                new FixItemDTO("Use minimal relevant logs and remove noise", "low"));
        return new AiDiagnosticsResponseDTO(
                "AI unavailable",
                "AI provider is temporarily unavailable or returned invalid output.",
                checks,
                fixes);
    }

    public static AiDiagnosticsResponseDTO featureDisabled() {
        AiDiagnosticsResponseDTO base = aiUnavailable();
        base.setProbableRootCause("Vision diagnostics feature is disabled by configuration.");
        return base;
    }

    public static AiDiagnosticsResponseDTO demoDisabled() {
        AiDiagnosticsResponseDTO base = aiUnavailable();
        base.setProbableRootCause("Demo diagnostics is disabled because demo-trading.enabled=false.");
        return base;
    }
}
