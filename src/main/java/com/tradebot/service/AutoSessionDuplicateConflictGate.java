package com.tradebot.service;

import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.Recommendation;
import com.tradebot.repository.LiveTradeExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AutoSessionDuplicateConflictGate {

    private static final String ACTIVE_SYMBOL_CONFLICT = "ACTIVE_SYMBOL_CONFLICT";
    private static final List<LiveTradeExecutionState> ACTIVE_EXECUTION_STATES = Arrays.stream(LiveTradeExecutionState.values())
            .filter(LiveTradeExecutionState::isActive)
            .toList();

    private final LiveTradeExecutionRepository liveTradeExecutionRepository;

    public DuplicateConflictDecision evaluate(Recommendation recommendation) {
        if (recommendation == null || recommendation.getSymbol() == null || recommendation.getSymbol().isBlank()) {
            return new DuplicateConflictDecision(
                    false,
                    recommendation != null ? recommendation.getSymbol() : null,
                    null,
                    null,
                    "INVALID_RECOMMENDATION",
                    "Recommendation symbol is missing.");
        }

        LiveTradeExecution conflictingExecution = liveTradeExecutionRepository
                .findFirstBySymbolAndExecutionStatusInOrderByCreatedAtDesc(
                        recommendation.getSymbol(),
                        ACTIVE_EXECUTION_STATES)
                .orElse(null);
        if (conflictingExecution == null) {
            return new DuplicateConflictDecision(true, recommendation.getSymbol(), null, null, null, null);
        }

        return new DuplicateConflictDecision(
                false,
                recommendation.getSymbol(),
                conflictingExecution.getId(),
                conflictingExecution.getExecutionState().name(),
                ACTIVE_SYMBOL_CONFLICT,
                "Symbol already has an active live execution.");
    }
}
