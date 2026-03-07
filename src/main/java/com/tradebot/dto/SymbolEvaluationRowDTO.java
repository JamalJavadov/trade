package com.tradebot.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class SymbolEvaluationRowDTO {
    private String symbol;
    private String decision;
    private String side;
    private String bias;
    private Integer rank;
    private Integer rankInUniverse;
    private BigDecimal quoteVolumeUsdt;
    private Double finalScore;
    private Double confidenceScore;
    private Double confidence;
    private Double rrTp1;
    private String entry;
    private String sl;
    private String tp1;
    private String skipReasonCode;
    private String skipReasonText;
    private String traceId;
    private Boolean recommendationEligible;
    private Integer finalIntegrityScore;
    private String conflictState;
    private String aiAgreementState;
    private String aiReviewStatus;
    private List<String> rejectionReasons;
    private String latestCandidateStage;
    private String latestCandidateStageStatus;
    private java.time.Instant createdAt;
}
