package com.tradebot.dto;

import lombok.Data;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

@Data
public class AiSuggestionBatchDTO {
    private UUID id;
    private Instant createdAt;
    private Integer basedOnLastNTrades;
    private String summary;
    private String status;
    private List<SuggestionItemDTO> items;

    @Data
    public static class SuggestionItemDTO {
        private String key;
        private String proposedValue;
        private String reason;
        private String impactHypothesis;
        private String status;
    }
}
