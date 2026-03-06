package com.tradebot.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRequest {
    private AiTaskType taskType;
    private String systemPrompt;
    private String userPrompt;
    private String jsonSchemaHint;
    private Double temperature;
    private Integer maxTokens;

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
