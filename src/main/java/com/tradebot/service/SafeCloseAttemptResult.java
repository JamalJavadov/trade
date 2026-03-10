package com.tradebot.service;

import com.tradebot.dto.LiveTradeExecutionDTO;

import java.util.Map;
import java.util.UUID;

public record SafeCloseAttemptResult(
        UUID executionId,
        SafeCloseAttemptStatus status,
        LiveTradeExecutionDTO execution,
        String errorCode,
        String errorMessage,
        Map<String, Object> details) {

    public enum SafeCloseAttemptStatus {
        SUBMITTED,
        ALREADY_SUBMITTED,
        NO_POSITION,
        TIMED_OUT,
        FAILED,
        RESOLVED_FLAT
    }

    public boolean countedAsFailure() {
        return status == SafeCloseAttemptStatus.TIMED_OUT || status == SafeCloseAttemptStatus.FAILED;
    }
}
