package com.tradebot.entity;

import java.util.EnumSet;
import java.util.Set;

public enum LiveTradeExecutionState {
    REQUESTED,
    BLOCKED,
    DRY_RUN,
    SUBMITTING,
    ENTRY_SUBMITTED,
    PROTECTION_SUBMITTED,
    OPEN,
    PENDING_RECONCILE,
    RECONCILED,
    PROTECTION_FAILED,
    EMERGENCY_CLOSE_SUBMITTED,
    EMERGENCY_CLOSE_FAILED,
    FAILED;

    private static final Set<LiveTradeExecutionState> ACTIVE_STATES = EnumSet.of(
            REQUESTED,
            SUBMITTING,
            ENTRY_SUBMITTED,
            PROTECTION_SUBMITTED,
            OPEN,
            PENDING_RECONCILE,
            EMERGENCY_CLOSE_SUBMITTED);

    public boolean isActive() {
        return ACTIVE_STATES.contains(this);
    }
}
