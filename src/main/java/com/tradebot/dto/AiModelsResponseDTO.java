package com.tradebot.dto;

import com.tradebot.ai.AiTaskType;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiModelsResponseDTO {
    private String mode;
    private boolean controlsEnabled;
    private List<String> allowlist = new ArrayList<>();
    private List<TaskModelDTO> tasks = new ArrayList<>();

    @Data
    public static class TaskModelDTO {
        private AiTaskType taskType;
        private String primaryModel;
        private List<String> fallbackModels = new ArrayList<>();
        private LastCallDTO lastCall;
    }

    @Data
    public static class LastCallDTO {
        private String status;
        private String traceId;
        private Long latencyMs;
        private String modelUsed;
        private Instant calledAt;
    }
}
