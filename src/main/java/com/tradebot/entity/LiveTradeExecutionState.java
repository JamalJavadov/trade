package com.tradebot.entity;

import java.util.EnumSet;
import java.util.Set;

public enum LiveTradeExecutionState {
    CREATED,
    PREFLIGHT_VALIDATING,
    PREFLIGHT_REJECTED,
    ENTRY_SUBMITTING,
    ENTRY_SUBMITTED,
    ENTRY_FILLED,
    PROTECTION_SUBMITTING,
    PROTECTION_ACTIVE,
    ACTIVE,
    CLOSING,
    RECONCILING,
    CLOSED,
    FAILED;

    private static final Set<LiveTradeExecutionState> ACTIVE_STATES = EnumSet.of(
            CREATED,
            PREFLIGHT_VALIDATING,
            ENTRY_SUBMITTING,
            ENTRY_SUBMITTED,
            ENTRY_FILLED,
            PROTECTION_SUBMITTING,
            PROTECTION_ACTIVE,
            ACTIVE,
            CLOSING,
            RECONCILING);

    public boolean isActive() {
        return ACTIVE_STATES.contains(this);
    }

    public boolean isTerminal() {
        return this == PREFLIGHT_REJECTED || this == CLOSED || this == FAILED;
    }
}
