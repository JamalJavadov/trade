package com.tradebot.dto;

import com.tradebot.ai.AiTaskType;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AiModelsTestResponseDTO {
    private String mode;
    private AiTaskType taskType;
    private boolean ok;
    private boolean simulated;
    private String traceId;
    private long latencyMs;
    private String modelUsed;
    private Map<String, Object> payload = new LinkedHashMap<>();
}
