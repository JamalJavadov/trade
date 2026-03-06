package com.tradebot.demo.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
public class DemoAiLatestResponseDTO {
    private BatchDTO batch;
    private List<ItemDTO> items;
    private ConfigVersionDTO activeConfigVersion;

    @Data
    public static class BatchDTO {
        private UUID id;
        private Instant createdAt;
        private Integer basedOnLastNTrades;
        private String status;
        private String summary;
        private String model;
        private String promptJson;
        private String responseJson;
        private String errorJson;
        private Instant acceptedAt;
        private String acceptedBy;
        private Instant rejectedAt;
        private String rejectReason;
        private Instant failedAt;
        private String errorCode;
        private String traceId;
        private Long latencyMs;
        private String callStatus;
    }

    @Data
    public static class ItemDTO {
        private UUID batchId;
        private String key;
        private String proposedValue;
        private String reason;
        private String impactHypothesis;
        private String riskOfChange;
        private String status;
    }

    @Data
    public static class ConfigVersionDTO {
        private UUID id;
        private Integer version;
        private Instant createdAt;
        private Boolean active;
        private String configJson;
        private String changeReason;
    }
}
