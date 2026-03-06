package com.tradebot.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class RiskPreviewResponseDTO {
    private BigDecimal riskUsdtFromEquity;
    private BigDecimal riskUsdtFromBudget;
    private BigDecimal effectiveRiskUsdt;
    private List<String> notes;
}
