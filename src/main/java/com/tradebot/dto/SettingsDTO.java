package com.tradebot.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SettingsDTO {
    private boolean safeMode;
    private boolean schedulerEnabled;

    @Min(value = 1, message = "Scan interval must be at least 1 minute")
    private int scanIntervalMinutes;

    @Positive(message = "Budget USDT must be strictly positive")
    private BigDecimal budgetUsdt;

    @DecimalMin(value = "0.1", message = "Max budget percentage must be at least 0.1")
    @DecimalMax(value = "20.0", message = "Max budget percentage must not exceed 20.0")
    private BigDecimal maxBudgetPct;

    @Positive(message = "Equity override USDT must be strictly positive")
    private BigDecimal equityOverrideUsdt;

    private BigDecimal maxEquityPct; // Read-only typically, locked to 1.00
}
