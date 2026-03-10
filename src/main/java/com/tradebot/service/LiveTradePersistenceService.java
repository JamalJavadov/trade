package com.tradebot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.LiveTradeClosure;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.LiveTradeOrder;
import com.tradebot.entity.LiveTradePnlLedger;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.repository.LiveTradeClosureRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.LiveTradeOrderRepository;
import com.tradebot.repository.LiveTradePnlLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LiveTradePersistenceService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final LiveTradeOrderRepository liveTradeOrderRepository;
    private final LiveTradeClosureRepository liveTradeClosureRepository;
    private final LiveTradePnlLedgerRepository liveTradePnlLedgerRepository;
    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final BudgetTargetSessionRepository budgetTargetSessionRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public LiveTradeOrder upsertOrder(LiveTradeExecution execution,
            String orderRole,
            String clientOrderId,
            Long exchangeOrderId,
            String clientAlgoId,
            Long exchangeAlgoId,
            BigDecimal requestedQty,
            BigDecimal executedQty,
            BigDecimal limitPrice,
            BigDecimal triggerPrice,
            BigDecimal avgFillPrice,
            String orderStatus,
            Object requestPayload,
            Object responsePayload,
            Object snapshotPayload) {
        LiveTradeOrder order = liveTradeOrderRepository.findByExecution_IdAndOrderRole(execution.getId(), orderRole)
                .orElseGet(LiveTradeOrder::new);
        if (order.getId() == null) {
            order.setExecution(execution);
            order.setSession(execution.getSession());
            order.setSymbol(execution.getSymbol());
            order.setOrderRole(orderRole);
            order.setCreatedAt(Instant.now());
        }
        order.setSession(execution.getSession());
        order.setSymbol(execution.getSymbol());
        order.setClientOrderId(clientOrderId);
        order.setExchangeOrderId(exchangeOrderId);
        order.setClientAlgoId(clientAlgoId);
        order.setExchangeAlgoId(exchangeAlgoId);
        order.setRequestedQty(requestedQty);
        order.setExecutedQty(executedQty);
        order.setLimitPrice(limitPrice);
        order.setTriggerPrice(triggerPrice);
        order.setAvgFillPrice(avgFillPrice);
        order.setOrderStatus(orderStatus);
        order.setRequestJson(writeJson(requestPayload));
        order.setResponseJson(writeJson(responsePayload));
        order.setSnapshotJson(writeJson(snapshotPayload));
        order.setTraceId(execution.getTraceId());
        order.setUpdatedAt(Instant.now());
        return liveTradeOrderRepository.save(order);
    }

    @Transactional
    public LiveTradeClosure upsertClosure(LiveTradeExecution execution,
            String closeReason,
            BigDecimal closedQty,
            BigDecimal closedPrice,
            String closingClientOrderId,
            Long closingOrderId,
            Object finalPositionSnapshot,
            Object closeResponsePayload,
            Instant closedAt) {
        LiveTradeClosure closure = liveTradeClosureRepository.findByExecution_Id(execution.getId())
                .orElseGet(LiveTradeClosure::new);
        if (closure.getId() == null) {
            closure.setExecution(execution);
        }
        closure.setSession(execution.getSession());
        closure.setCloseReason(closeReason);
        closure.setClosedQty(closedQty);
        closure.setClosedPrice(closedPrice);
        closure.setClosingClientOrderId(closingClientOrderId);
        closure.setClosingOrderId(closingOrderId);
        closure.setFinalPositionSnapshotJson(writeJson(finalPositionSnapshot));
        closure.setCloseResponseJson(writeJson(closeResponsePayload));
        closure.setTraceId(execution.getTraceId());
        closure.setClosedAt(closedAt != null ? closedAt : Instant.now());
        return liveTradeClosureRepository.save(closure);
    }

    @Transactional
    public LiveTradePnlLedger upsertLedgerEntry(BudgetTargetSession session,
            LiveTradeExecution execution,
            String eventType,
            BigDecimal amountUsdt,
            Instant eventTs,
            String sourceType,
            String sourceRef,
            Object beforeState,
            Object afterState,
            String notes,
            String traceId) {
        if (session == null || sourceType == null || sourceRef == null) {
            return null;
        }
        LiveTradePnlLedger entry = liveTradePnlLedgerRepository.findBySourceTypeAndSourceRef(sourceType, sourceRef)
                .orElseGet(LiveTradePnlLedger::new);
        entry.setSession(session);
        entry.setExecution(execution);
        entry.setEventType(eventType);
        entry.setAmountUsdt(amountUsdt == null ? BigDecimal.ZERO : amountUsdt);
        entry.setEventTs(eventTs != null ? eventTs : Instant.now());
        entry.setSourceType(sourceType);
        entry.setSourceRef(sourceRef);
        entry.setBeforeJson(writeJson(beforeState));
        entry.setAfterJson(writeJson(afterState));
        entry.setNotes(notes);
        entry.setTraceId(traceId);
        return liveTradePnlLedgerRepository.save(entry);
    }

    @Transactional
    public void refreshExecutionNetPnl(LiveTradeExecution execution) {
        BigDecimal realizedNetPnl = execution.getSession() == null
                ? execution.getRealizedNetPnlUsdt()
                : liveTradePnlLedgerRepository.sumNetPnlByExecutionId(execution.getId());
        execution.setRealizedNetPnlUsdt(realizedNetPnl == null ? BigDecimal.ZERO : realizedNetPnl);
        liveTradeExecutionRepository.save(execution);
    }

    @Transactional
    public void refreshSessionRollup(BudgetTargetSession session, Collection<LiveTradeExecutionState> activeStates) {
        if (session == null) {
            return;
        }
        List<LiveTradeExecution> executions = liveTradeExecutionRepository.findBySession_IdOrderByCreatedAtDesc(session.getId());
        List<LiveTradeExecution> activeExecutions = liveTradeExecutionRepository
                .findBySession_IdAndExecutionStatusInOrderByCreatedAtAsc(session.getId(), activeStates);

        int openedPositionsTotal = (int) executions.stream().filter(this::hasOpenedPosition).count();
        int closedPositionsTotal = (int) executions.stream()
                .filter(this::hasOpenedPosition)
                .filter(execution -> !execution.getExecutionStatus().isActive())
                .count();
        BigDecimal realizedNetPnl = liveTradePnlLedgerRepository.sumNetPnlBySessionId(session.getId());
        BigDecimal unrealizedNetPnl = activeExecutions.stream()
                .map(this::extractUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        session.setRealizedNetPnlUsdt(realizedNetPnl == null ? BigDecimal.ZERO : realizedNetPnl);
        session.setUnrealizedNetPnlUsdt(unrealizedNetPnl);
        session.setActivePositionsCount(activeExecutions.size());
        session.setOpenedPositionsTotal(openedPositionsTotal);
        session.setClosedPositionsTotal(closedPositionsTotal);
        session.setUpdatedAt(Instant.now());
        budgetTargetSessionRepository.save(session);
    }

    private boolean hasOpenedPosition(LiveTradeExecution execution) {
        return execution.getActualFilledQty() != null && execution.getActualFilledQty().compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal extractUnrealizedPnl(LiveTradeExecution execution) {
        Map<String, Object> payload = readJson(execution.getExchangeResponseJson());
        Object position = payload.get("position");
        if (!(position instanceof Map<?, ?> positionMap)) {
            return BigDecimal.ZERO;
        }
        Object value = positionMap.get("unRealizedProfit");
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of("raw", json);
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }
}
