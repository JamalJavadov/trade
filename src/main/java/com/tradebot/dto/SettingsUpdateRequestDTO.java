package com.tradebot.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SettingsUpdateRequestDTO {

    private Boolean safeMode;
    private Boolean schedulerEnabled;

    @JsonAlias("intervalMinutes")
    @Min(value = 1, message = "Scan interval must be at least 1 minute")
    private Integer scanIntervalMinutes;

    @Positive(message = "Budget USDT must be strictly positive")
    private BigDecimal budgetUsdt;

    @JsonAlias("riskMaxBudgetPct")
    @DecimalMin(value = "0.1", message = "Max budget percentage must be at least 0.1")
    @DecimalMax(value = "20.0", message = "Max budget percentage must not exceed 20.0")
    private BigDecimal maxBudgetPct;

    @Positive(message = "Equity override USDT must be strictly positive")
    private BigDecimal equityOverrideUsdt;
    private boolean equityOverrideUsdtProvided;

    @JsonSetter("equityOverrideUsdt")
    public void setEquityOverrideUsdt(BigDecimal equityOverrideUsdt) {
        this.equityOverrideUsdtProvided = true;
        this.equityOverrideUsdt = equityOverrideUsdt;
    }

    public boolean hasSchedulingChanges() {
        return safeMode != null || schedulerEnabled != null || scanIntervalMinutes != null;
    }

    public boolean hasRiskBudgetChanges() {
        return budgetUsdt != null || maxBudgetPct != null || equityOverrideUsdtProvided;
    }
}
