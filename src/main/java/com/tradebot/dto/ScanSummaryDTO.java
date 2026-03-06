package com.tradebot.dto;

import lombok.Data;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
public class ScanSummaryDTO {
    private UUID id;
    private Instant startedAt;
    private Instant finishedAt;
    private String status;
    private Integer intervalMinutes;
    private Integer topN;
    private long eligibleSymbolCount;
    private long evaluatedCount;
    private long validCount;
    private long noTradeCount;
    private String triggerType;
    private String errorCode;
    private String correlationId;
    private String notes;
    private UUID bestRecommendationId;
    private List<PhaseDTO> phases;
}
