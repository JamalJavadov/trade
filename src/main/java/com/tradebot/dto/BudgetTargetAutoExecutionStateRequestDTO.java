package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class BudgetTargetAutoExecutionStateRequestDTO {

    private Command command;
    private Boolean confirmStop;
    private String reason;
    private BigDecimal budgetAmountUsdt;
    private BigDecimal targetProfitUsdt;

    public enum Command {
        TURN_ON,
        TURN_OFF
    }
}
