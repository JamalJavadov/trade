package com.tradebot.entity;

public enum BudgetTargetSessionStatus {
    DRAFT,
    ARMED,
    RUNNING,
    TARGET_REACHED,
    STOPPING,
    STOPPED,
    FAILED,
    CANCELLED;

    public boolean isActive() {
        return this == DRAFT || this == ARMED || this == RUNNING || this == TARGET_REACHED || this == STOPPING;
    }

    public boolean allowsTradeOpens() {
        return this == RUNNING;
    }

    public boolean isTerminal() {
        return this == STOPPED || this == FAILED || this == CANCELLED;
    }
}
