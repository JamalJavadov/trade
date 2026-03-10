package com.tradebot.service;

import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.LiveTradeExecution;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class AutoSessionBudgetAllocator {

    private static final String BANKROLL_EXHAUSTED = "BANKROLL_EXHAUSTED";

    public BudgetAllocationDecision allocate(BudgetTargetSession session,
            List<LiveTradeExecution> activeExecutions,
            ActivePositionGateDecision positionGateDecision) {
        BigDecimal sessionBudgetUsdt = normalize(session != null ? session.getBudgetAmountUsdt() : null);
        BigDecimal realizedNetPnlUsdt = normalize(session != null ? session.getRealizedNetPnlUsdt() : null);
        BigDecimal unrealizedNetPnlUsdt = normalize(session != null ? session.getUnrealizedNetPnlUsdt() : null);
        BigDecimal reservedActiveExposureUsdt = activeExecutions == null
                ? BigDecimal.ZERO
                : activeExecutions.stream()
                        .map(LiveTradeExecution::getReservedMarginUsdt)
                        .map(this::normalize)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        int activePositionsCount = positionGateDecision != null ? positionGateDecision.activePositionsCount() : 0;
        int freeSlots = positionGateDecision != null ? positionGateDecision.freeSlots() : 0;

        BigDecimal spendableBudgetUsdt = sessionBudgetUsdt
                .add(realizedNetPnlUsdt)
                .subtract(reservedActiveExposureUsdt);
        BigDecimal allocatedSliceUsdt = freeSlots > 0
                ? spendableBudgetUsdt.divide(BigDecimal.valueOf(freeSlots), 8, RoundingMode.DOWN)
                : BigDecimal.ZERO;
        if (allocatedSliceUsdt.compareTo(spendableBudgetUsdt) > 0) {
            allocatedSliceUsdt = spendableBudgetUsdt;
        }

        if (spendableBudgetUsdt.compareTo(BigDecimal.ZERO) <= 0 || allocatedSliceUsdt.compareTo(BigDecimal.ZERO) <= 0) {
            return new BudgetAllocationDecision(
                    false,
                    sessionBudgetUsdt,
                    realizedNetPnlUsdt,
                    unrealizedNetPnlUsdt,
                    reservedActiveExposureUsdt,
                    spendableBudgetUsdt,
                    BigDecimal.ZERO,
                    activePositionsCount,
                    freeSlots,
                    BANKROLL_EXHAUSTED,
                    "Remaining usable budget is zero or negative.");
        }

        return new BudgetAllocationDecision(
                true,
                sessionBudgetUsdt,
                realizedNetPnlUsdt,
                unrealizedNetPnlUsdt,
                reservedActiveExposureUsdt,
                spendableBudgetUsdt,
                allocatedSliceUsdt,
                activePositionsCount,
                freeSlots,
                null,
                null);
    }

    private BigDecimal normalize(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
