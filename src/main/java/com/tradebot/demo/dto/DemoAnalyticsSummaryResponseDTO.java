package com.tradebot.demo.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public class DemoAnalyticsSummaryResponseDTO {
    private Integer lookback;
    private Instant generatedAt;
    private Map<String, Object> metrics;
    private Map<String, List<Map<String, Object>>> cohorts;
    private List<Map<String, Object>> topFailurePatterns;
    private DemoAiLatestResponseDTO.ConfigVersionDTO activeConfigVersion;
}
