package com.tradebot.dto;

import lombok.Data;
import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;

@Data
public class RiskPreviewRequestDTO {
    @Positive(message = "Budget USDT must be strictly positive")
    private BigDecimal budgetUsdt;

    @DecimalMin(value = "0.1", message = "Max budget percentage must be at least 0.1")
    @DecimalMax(value = "20.0", message = "Max budget percentage must not exceed 20.0")
    private BigDecimal maxBudgetPct;

    @Positive(message = "Equity override USDT must be strictly positive")
    private BigDecimal equityOverrideUsdt;

    private BigDecimal maxEquityPct = new BigDecimal("1.00");
}
