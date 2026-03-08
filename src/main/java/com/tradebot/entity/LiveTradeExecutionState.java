package com.tradebot.entity;

import java.util.EnumSet;
import java.util.Set;

public enum LiveTradeExecutionState {
    REQUESTED,
    BLOCKED,
    DRY_RUN,
    SUBMITTING,
    ENTRY_SUBMITTED,
    ENTRY_PARTIALLY_FILLED,
    ENTRY_FILLED,
    PROTECTION_SUBMITTING,
    PROTECTION_SUBMITTED,
    PROTECTION_ACTIVE,
    OPEN,
    RECONCILING,
    PENDING_RECONCILE,
    RECONCILED,
    PROTECTION_FAILED,
    EMERGENCY_CLOSE_SUBMITTED,
    EMERGENCY_CLOSE_FILLED,
    EMERGENCY_CLOSE_FAILED,
    FAILED;

    private static final Set<LiveTradeExecutionState> ACTIVE_STATES = EnumSet.of(
            REQUESTED,
            SUBMITTING,
            ENTRY_SUBMITTED,
            ENTRY_PARTIALLY_FILLED,
            ENTRY_FILLED,
            PROTECTION_SUBMITTING,
            PROTECTION_SUBMITTED,
            PROTECTION_ACTIVE,
            OPEN,
            RECONCILING,
            PENDING_RECONCILE,
            PROTECTION_FAILED,
            EMERGENCY_CLOSE_SUBMITTED);

    public boolean isActive() {
        return ACTIVE_STATES.contains(this);
    }
}
