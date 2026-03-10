package com.tradebot.service;

import com.tradebot.entity.BudgetTargetSession;
import org.springframework.stereotype.Service;

@Service
public class AutoSessionActivePositionGate {

    public static final int HARD_MAX_CONCURRENT_POSITIONS = 3;
    private static final String ACTIVE_LIMIT_REACHED = "ACTIVE_LIMIT_REACHED";

    public ActivePositionGateDecision evaluate(BudgetTargetSession session, int activePositionsCount) {
        int normalizedActiveCount = Math.max(0, activePositionsCount);
        int configuredLimit = session != null && session.getMaxConcurrentPositions() > 0
                ? session.getMaxConcurrentPositions()
                : HARD_MAX_CONCURRENT_POSITIONS;
        int effectiveLimit = Math.min(HARD_MAX_CONCURRENT_POSITIONS, configuredLimit);
        int freeSlots = Math.max(0, effectiveLimit - normalizedActiveCount);
        if (freeSlots == 0) {
            return new ActivePositionGateDecision(
                    false,
                    normalizedActiveCount,
                    effectiveLimit,
                    0,
                    ACTIVE_LIMIT_REACHED,
                    "Active position limit reached for the session.");
        }
        return new ActivePositionGateDecision(
                true,
                normalizedActiveCount,
                effectiveLimit,
                freeSlots,
                null,
                null);
    }
}
