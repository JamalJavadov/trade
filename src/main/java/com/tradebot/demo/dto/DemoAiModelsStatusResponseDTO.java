package com.tradebot.demo.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class DemoAiModelsStatusResponseDTO {
    private boolean enabled;
    private Map<String, List<String>> routing;
    private LastCallDTO lastCall;

    @Data
    public static class LastCallDTO {
        private String status;
        private String modelUsed;
        private String errorCode;
        private String traceId;
        private Long latencyMs;
    }
}
