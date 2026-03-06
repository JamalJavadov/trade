package com.tradebot.dto;

import lombok.Data;
import java.math.BigDecimal;

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
}
