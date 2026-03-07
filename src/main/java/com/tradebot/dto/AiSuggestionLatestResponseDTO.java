package com.tradebot.dto;

import lombok.Data;

@Data
public class AiSuggestionLatestResponseDTO {
    private Integer currentActiveConfigVersion;
    private AiSuggestionBatchDTO batch;
}
