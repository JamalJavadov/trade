package com.tradebot.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
public class BestCandidateEventDTO {
    private UUID id;
    private UUID scanRunId;
    private Instant ts;
    private String symbol;
    private String side;
    private BigDecimal finalScore;
    private UUID recommendationId;
    private String reasonJson;
}
