package com.tradebot;

import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.service.ActivePositionGateDecision;
import com.tradebot.service.AutoSessionBudgetAllocator;
import com.tradebot.service.BudgetAllocationDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSessionBudgetAllocatorTest {

    private final AutoSessionBudgetAllocator allocator = new AutoSessionBudgetAllocator();

    @Test
    void allocationNeverExceedsRemainingSpendableBudgetAndIgnoresUnrealizedPnl() {
        BudgetTargetSession session = new BudgetTargetSession();
        session.setId(UUID.randomUUID());
        session.setBudgetAmountUsdt(new BigDecimal("100"));
        session.setRealizedNetPnlUsdt(new BigDecimal("20"));
        session.setUnrealizedNetPnlUsdt(new BigDecimal("999"));
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setConfigSnapshotJson("{}");

        ActivePositionGateDecision positionGate = new ActivePositionGateDecision(true, 2, 3, 1, null, null);
        BudgetAllocationDecision decision = allocator.allocate(
                session,
                List.of(
                        execution(new BigDecimal("30")),
                        execution(new BigDecimal("10"))),
                positionGate);

        assertTrue(decision.allowed());
        assertEquals(new BigDecimal("40"), decision.reservedActiveExposureUsdt());
        assertEquals(new BigDecimal("80"), decision.spendableBudgetUsdt());
        assertEquals(new BigDecimal("80.00000000"), decision.allocatedSliceUsdt());
        assertTrue(decision.allocatedSliceUsdt().compareTo(decision.spendableBudgetUsdt()) <= 0);
        assertEquals(new BigDecimal("999"), decision.unrealizedNetPnlUsdt());
    }

    private LiveTradeExecution execution(BigDecimal reservedMarginUsdt) {
        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setId(UUID.randomUUID());
        execution.setReservedMarginUsdt(reservedMarginUsdt);
        execution.setCreatedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        return execution;
    }
}
