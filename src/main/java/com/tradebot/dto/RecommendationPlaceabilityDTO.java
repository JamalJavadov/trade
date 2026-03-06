package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
public class RecommendationPlaceabilityDTO {
    private UUID recommendationId;
    private String symbol;
    private String side;
    private BigDecimal markPrice;
    private BigDecimal tickSize;
    private boolean placeable;
    private String reasonCode;
    private String reasonText;
    private Rules rules;
    private Checks checks;
    private Computed computed;

    // Compatibility fields for existing UI consumers.
    private BigDecimal tpRaw;
    private BigDecimal slRaw;
    private BigDecimal tpDisplay;
    private BigDecimal slDisplay;
    private String ruleText;
    private String requiredInequality;
    private BigDecimal liveRrToTp1;
    private BigDecimal minRrRequired;
    private boolean manualPlacementAllowed;
    private List<String> violations;
    private List<String> adjustments;
    private Instant checkedAt;

    @Data
    public static class Rules {
        private String inequalityRule;
        private Integer minTickGap;
    }

    @Data
    public static class Checks {
        private boolean tpOk;
        private boolean slOk;
        private boolean rrOk;
    }

    @Data
    public static class Computed {
        private String entryRef;
        private BigDecimal rrToTp1;
        private BigDecimal tp1;
        private BigDecimal sl;
        private BigDecimal suggestedTp1Adjusted;
        private BigDecimal suggestedSlAdjusted;
    }
}
