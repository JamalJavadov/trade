package com.tradebot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.dto.BudgetTargetSyncHealthDTO;
import com.tradebot.dto.ExchangeSyncSnapshotDTO;
import com.tradebot.entity.ExchangeSyncSnapshot;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.repository.ExchangeSyncSnapshotRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExchangeSyncSnapshotService {

    public static final String SYNC_STATUS_SUCCESS = "SUCCESS";
    public static final String SYNC_STATUS_FAILURE = "FAILURE";
    public static final String SYNC_STATUS_SKIPPED = "SKIPPED";

    public static final String HEALTHY = "HEALTHY";
    public static final String FAILED = "FAILED";
    public static final String DIVERGED = "DIVERGED";
    public static final String STALE = "STALE";
    public static final String PENDING_FIRST_SYNC = "PENDING_FIRST_SYNC";
    public static final String IDLE = "IDLE";

    public static final String EXCHANGE_SYNC_FAILED = "EXCHANGE_SYNC_FAILED";
    public static final String EXCHANGE_SYNC_DIVERGED = "EXCHANGE_SYNC_DIVERGED";
    public static final String EXCHANGE_SYNC_STALE = "EXCHANGE_SYNC_STALE";
    public static final String EXCHANGE_SYNC_UNCONFIRMED = "EXCHANGE_SYNC_UNCONFIRMED";

    private static final Duration STALE_AFTER = Duration.ofSeconds(90);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final List<LiveTradeExecutionState> ACTIVE_EXECUTION_STATES = java.util.Arrays.stream(LiveTradeExecutionState.values())
            .filter(LiveTradeExecutionState::isActive)
            .toList();

    private final ExchangeSyncSnapshotRepository exchangeSyncSnapshotRepository;
    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ExchangeSyncSnapshot persistSnapshot(RecordedSyncSnapshot recorded) {
        ExchangeSyncSnapshot snapshot = new ExchangeSyncSnapshot();
        snapshot.setSession(recorded.execution().getSession());
        snapshot.setExecution(recorded.execution());
        snapshot.setSymbol(recorded.execution().getSymbol());
        snapshot.setSyncType(recorded.syncType());
        snapshot.setSyncStatus(recorded.syncStatus());
        snapshot.setTraceId(recorded.traceId());
        snapshot.setErrorCode(recorded.errorCode());
        snapshot.setErrorMessage(recorded.errorMessage());
        snapshot.setDivergenceDetected(recorded.divergenceDetected());
        snapshot.setRequiresIntervention(recorded.requiresIntervention());
        snapshot.setOpenPosition(recorded.openPosition());
        snapshot.setActiveOpenOrderCount(recorded.activeOpenOrderCount());
        snapshot.setActiveProtectionOrderCount(recorded.activeProtectionOrderCount());
        snapshot.setStopLossActive(recorded.stopLossActive());
        snapshot.setTakeProfitActive(recorded.takeProfitActive());
        snapshot.setEmergencyCloseWorking(recorded.emergencyCloseWorking());
        snapshot.setEmergencyCloseFilled(recorded.emergencyCloseFilled());
        snapshot.setProtectionTriggered(recorded.protectionTriggered());
        snapshot.setEntryOrderStatus(recorded.entryOrderStatus());
        snapshot.setStopLossStatus(recorded.stopLossStatus());
        snapshot.setTakeProfitStatus(recorded.takeProfitStatus());
        snapshot.setEmergencyCloseStatus(recorded.emergencyCloseStatus());
        snapshot.setPositionQuantity(recorded.positionQuantity());
        snapshot.setActualFilledQty(recorded.actualFilledQty());
        snapshot.setAvgFillPrice(recorded.avgFillPrice());
        snapshot.setEntryPrice(recorded.entryPrice());
        snapshot.setMarkPrice(recorded.markPrice());
        snapshot.setRealizedGrossPnlUsdt(recorded.realizedGrossPnlUsdt());
        snapshot.setRealizedFeesUsdt(recorded.realizedFeesUsdt());
        snapshot.setRealizedNetPnlUsdt(recorded.realizedNetPnlUsdt());
        snapshot.setUnrealizedPnlUsdt(recorded.unrealizedPnlUsdt());
        snapshot.setLastSuccessfulSyncAt(resolveLastSuccessfulSyncAt(recorded.execution().getId(), recorded));
        snapshot.setSyncCompletedAt(recorded.syncCompletedAt());
        snapshot.setSnapshotJson(writeJson(recorded.snapshotPayload()));
        snapshot.setCreatedAt(recorded.syncCompletedAt());
        return exchangeSyncSnapshotRepository.save(snapshot);
    }

    @Transactional(readOnly = true)
    public BudgetTargetSyncHealthDTO summarizeExecution(UUID executionId) {
        ExchangeSyncSnapshot latest = exchangeSyncSnapshotRepository
                .findFirstByExecution_IdOrderBySyncCompletedAtDesc(executionId)
                .orElse(null);
        return latest == null ? emptyHealth() : toHealth(latest, false);
    }

    @Transactional(readOnly = true)
    public BudgetTargetSyncHealthDTO summarizeSession(UUID sessionId) {
        List<LiveTradeExecution> activeExecutions = liveTradeExecutionRepository
                .findBySession_IdAndExecutionStatusInOrderByCreatedAtAsc(sessionId, ACTIVE_EXECUTION_STATES);
        if (!activeExecutions.isEmpty()) {
            return summarizeActiveSession(activeExecutions);
        }

        ExchangeSyncSnapshot latest = exchangeSyncSnapshotRepository
                .findFirstBySession_IdOrderBySyncCompletedAtDesc(sessionId)
                .orElse(null);
        return latest == null ? idleHealth() : toHealth(latest, false);
    }

    @Transactional(readOnly = true)
    public Optional<SyncGateDecision> evaluateNewTradeGate(UUID sessionId) {
        List<LiveTradeExecution> activeExecutions = liveTradeExecutionRepository
                .findBySession_IdAndExecutionStatusInOrderByCreatedAtAsc(sessionId, ACTIVE_EXECUTION_STATES);
        if (activeExecutions.isEmpty()) {
            return Optional.empty();
        }
        BudgetTargetSyncHealthDTO health = summarizeActiveSession(activeExecutions);
        if (!health.isGateNewTrades()) {
            return Optional.empty();
        }
        return Optional.of(new SyncGateDecision(
                health.getGateReasonCode(),
                health.getGateReasonMessage(),
                health));
    }

    @Transactional(readOnly = true)
    public List<ExchangeSyncSnapshotDTO> listSnapshots(UUID executionId, int limit) {
        List<ExchangeSyncSnapshot> rows = exchangeSyncSnapshotRepository.findTop50ByExecution_IdOrderBySyncCompletedAtDesc(executionId);
        return rows.stream()
                .limit(Math.max(limit, 0))
                .map(this::toDto)
                .toList();
    }

    public boolean isSyncHealthGateCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return Set.of(EXCHANGE_SYNC_FAILED, EXCHANGE_SYNC_DIVERGED, EXCHANGE_SYNC_STALE, EXCHANGE_SYNC_UNCONFIRMED)
                .contains(code);
    }

    private Instant resolveLastSuccessfulSyncAt(UUID executionId, RecordedSyncSnapshot recorded) {
        if (SYNC_STATUS_SUCCESS.equals(recorded.syncStatus())) {
            return recorded.syncCompletedAt();
        }
        return exchangeSyncSnapshotRepository
                .findFirstByExecution_IdAndSyncStatusOrderBySyncCompletedAtDesc(executionId, SYNC_STATUS_SUCCESS)
                .map(ExchangeSyncSnapshot::getLastSuccessfulSyncAt)
                .orElse(null);
    }

    private BudgetTargetSyncHealthDTO summarizeActiveSession(List<LiveTradeExecution> activeExecutions) {
        List<UUID> executionIds = activeExecutions.stream().map(LiveTradeExecution::getId).toList();
        Map<UUID, ExchangeSyncSnapshot> latestByExecution = new LinkedHashMap<>();
        for (ExchangeSyncSnapshot snapshot : exchangeSyncSnapshotRepository.findByExecution_IdInOrderBySyncCompletedAtDesc(executionIds)) {
            latestByExecution.putIfAbsent(snapshot.getExecution().getId(), snapshot);
            if (latestByExecution.size() == executionIds.size()) {
                break;
            }
        }

        BudgetTargetSyncHealthDTO dto = new BudgetTargetSyncHealthDTO();
        dto.setCloseAllInProgress(activeExecutions.stream().anyMatch(execution -> execution.getExecutionState() == LiveTradeExecutionState.CLOSING));
        dto.setAffectedExecutionIds(new java.util.ArrayList<>(latestByExecution.keySet().stream().map(UUID::toString).toList()));

        if (latestByExecution.size() < executionIds.size()) {
            Set<String> affected = new LinkedHashSet<>(dto.getAffectedExecutionIds());
            for (UUID executionId : executionIds) {
                if (!latestByExecution.containsKey(executionId)) {
                    affected.add(executionId.toString());
                }
            }
            dto.setStatus(PENDING_FIRST_SYNC);
            dto.setGateNewTrades(true);
            dto.setGateReasonCode(EXCHANGE_SYNC_UNCONFIRMED);
            dto.setGateReasonMessage("At least one active session trade has not completed its first Binance sync yet.");
            dto.setAffectedExecutionIds(List.copyOf(affected));
            return dto;
        }

        Collection<ExchangeSyncSnapshot> latestSnapshots = latestByExecution.values();
        dto.setLastSyncAt(latestSnapshots.stream().map(ExchangeSyncSnapshot::getSyncCompletedAt).max(Instant::compareTo).orElse(null));
        dto.setLastSuccessfulSyncAt(latestSnapshots.stream()
                .map(ExchangeSyncSnapshot::getLastSuccessfulSyncAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null));
        dto.setOpenPositionCount((int) latestSnapshots.stream().filter(ExchangeSyncSnapshot::isOpenPosition).count());
        dto.setActiveOpenOrderCount(latestSnapshots.stream().mapToInt(ExchangeSyncSnapshot::getActiveOpenOrderCount).sum());
        dto.setActiveProtectionOrderCount(latestSnapshots.stream().mapToInt(ExchangeSyncSnapshot::getActiveProtectionOrderCount).sum());
        dto.setDivergenceDetected(latestSnapshots.stream().anyMatch(ExchangeSyncSnapshot::isDivergenceDetected));
        dto.setRequiresIntervention(latestSnapshots.stream().anyMatch(ExchangeSyncSnapshot::isRequiresIntervention));

        ExchangeSyncSnapshot newest = latestSnapshots.stream()
                .max(java.util.Comparator.comparing(ExchangeSyncSnapshot::getSyncCompletedAt))
                .orElse(null);
        if (newest != null) {
            dto.setLatestSyncType(newest.getSyncType());
            dto.setLatestErrorCode(newest.getErrorCode());
            dto.setLatestErrorMessage(newest.getErrorMessage());
        }

        if (dto.isDivergenceDetected()) {
            ExchangeSyncSnapshot diverged = latestSnapshots.stream().filter(ExchangeSyncSnapshot::isDivergenceDetected).findFirst().orElse(newest);
            dto.setStatus(DIVERGED);
            dto.setGateNewTrades(true);
            dto.setGateReasonCode(EXCHANGE_SYNC_DIVERGED);
            dto.setGateReasonMessage(diverged != null && diverged.getErrorMessage() != null
                    ? diverged.getErrorMessage()
                    : "A live session trade diverged from expected exchange protection or position state.");
            if (diverged != null) {
                dto.setLatestErrorCode(firstNonBlank(diverged.getErrorCode(), EXCHANGE_SYNC_DIVERGED));
                dto.setLatestErrorMessage(firstNonBlank(diverged.getErrorMessage(), dto.getGateReasonMessage()));
            }
            return dto;
        }

        if (latestSnapshots.stream().anyMatch(snapshot -> SYNC_STATUS_FAILURE.equals(snapshot.getSyncStatus()))) {
            ExchangeSyncSnapshot failed = latestSnapshots.stream()
                    .filter(snapshot -> SYNC_STATUS_FAILURE.equals(snapshot.getSyncStatus()))
                    .findFirst()
                    .orElse(newest);
            dto.setStatus(FAILED);
            dto.setGateNewTrades(true);
            dto.setGateReasonCode(firstNonBlank(failed != null ? failed.getErrorCode() : null, EXCHANGE_SYNC_FAILED));
            dto.setGateReasonMessage(firstNonBlank(
                    failed != null ? failed.getErrorMessage() : null,
                    "Binance sync failed for at least one active session trade."));
            dto.setLatestErrorCode(dto.getGateReasonCode());
            dto.setLatestErrorMessage(dto.getGateReasonMessage());
            return dto;
        }

        Instant lastSuccessfulSyncAt = dto.getLastSuccessfulSyncAt();
        if (lastSuccessfulSyncAt == null) {
            dto.setStatus(PENDING_FIRST_SYNC);
            dto.setGateNewTrades(true);
            dto.setGateReasonCode(EXCHANGE_SYNC_UNCONFIRMED);
            dto.setGateReasonMessage("At least one active session trade has not completed its first Binance sync yet.");
            return dto;
        }
        if (lastSuccessfulSyncAt.isBefore(Instant.now().minus(STALE_AFTER))) {
            dto.setStatus(STALE);
            dto.setGateNewTrades(true);
            dto.setGateReasonCode(EXCHANGE_SYNC_STALE);
            dto.setGateReasonMessage("The latest successful Binance sync for active session trades is stale.");
            return dto;
        }

        dto.setStatus(HEALTHY);
        return dto;
    }

    private BudgetTargetSyncHealthDTO toHealth(ExchangeSyncSnapshot snapshot, boolean gate) {
        BudgetTargetSyncHealthDTO dto = new BudgetTargetSyncHealthDTO();
        dto.setLastSyncAt(snapshot.getSyncCompletedAt());
        dto.setLastSuccessfulSyncAt(snapshot.getLastSuccessfulSyncAt());
        dto.setLatestSyncType(snapshot.getSyncType());
        dto.setLatestErrorCode(snapshot.getErrorCode());
        dto.setLatestErrorMessage(snapshot.getErrorMessage());
        dto.setDivergenceDetected(snapshot.isDivergenceDetected());
        dto.setRequiresIntervention(snapshot.isRequiresIntervention());
        dto.setOpenPositionCount(snapshot.isOpenPosition() ? 1 : 0);
        dto.setActiveOpenOrderCount(snapshot.getActiveOpenOrderCount());
        dto.setActiveProtectionOrderCount(snapshot.getActiveProtectionOrderCount());
        if (snapshot.isDivergenceDetected()) {
            dto.setStatus(DIVERGED);
            dto.setGateNewTrades(gate);
            dto.setGateReasonCode(EXCHANGE_SYNC_DIVERGED);
            dto.setGateReasonMessage(firstNonBlank(snapshot.getErrorMessage(),
                    "Exchange sync detected an execution divergence."));
        } else if (SYNC_STATUS_FAILURE.equals(snapshot.getSyncStatus())) {
            dto.setStatus(FAILED);
            dto.setGateNewTrades(gate);
            dto.setGateReasonCode(firstNonBlank(snapshot.getErrorCode(), EXCHANGE_SYNC_FAILED));
            dto.setGateReasonMessage(firstNonBlank(snapshot.getErrorMessage(),
                    "Exchange sync failed for the execution."));
        } else if (snapshot.getLastSuccessfulSyncAt() != null
                && snapshot.getLastSuccessfulSyncAt().isBefore(Instant.now().minus(STALE_AFTER))) {
            dto.setStatus(STALE);
            dto.setGateNewTrades(gate);
            dto.setGateReasonCode(EXCHANGE_SYNC_STALE);
            dto.setGateReasonMessage("The latest successful Binance sync for this execution is stale.");
        } else {
            dto.setStatus(HEALTHY);
        }
        dto.setAffectedExecutionIds(List.of(snapshot.getExecution().getId().toString()));
        return dto;
    }

    private BudgetTargetSyncHealthDTO emptyHealth() {
        BudgetTargetSyncHealthDTO dto = new BudgetTargetSyncHealthDTO();
        dto.setStatus(PENDING_FIRST_SYNC);
        dto.setGateNewTrades(true);
        dto.setGateReasonCode(EXCHANGE_SYNC_UNCONFIRMED);
        dto.setGateReasonMessage("No Binance sync snapshot has been recorded yet.");
        return dto;
    }

    private BudgetTargetSyncHealthDTO idleHealth() {
        BudgetTargetSyncHealthDTO dto = new BudgetTargetSyncHealthDTO();
        dto.setStatus(IDLE);
        return dto;
    }

    private ExchangeSyncSnapshotDTO toDto(ExchangeSyncSnapshot snapshot) {
        ExchangeSyncSnapshotDTO dto = new ExchangeSyncSnapshotDTO();
        dto.setId(snapshot.getId());
        dto.setSessionId(snapshot.getSession() != null ? snapshot.getSession().getId() : null);
        dto.setExecutionId(snapshot.getExecution().getId());
        dto.setSymbol(snapshot.getSymbol());
        dto.setSyncType(snapshot.getSyncType());
        dto.setSyncStatus(snapshot.getSyncStatus());
        dto.setTraceId(snapshot.getTraceId());
        dto.setErrorCode(snapshot.getErrorCode());
        dto.setErrorMessage(snapshot.getErrorMessage());
        dto.setDivergenceDetected(snapshot.isDivergenceDetected());
        dto.setRequiresIntervention(snapshot.isRequiresIntervention());
        dto.setOpenPosition(snapshot.isOpenPosition());
        dto.setActiveOpenOrderCount(snapshot.getActiveOpenOrderCount());
        dto.setActiveProtectionOrderCount(snapshot.getActiveProtectionOrderCount());
        dto.setStopLossActive(snapshot.isStopLossActive());
        dto.setTakeProfitActive(snapshot.isTakeProfitActive());
        dto.setEmergencyCloseWorking(snapshot.isEmergencyCloseWorking());
        dto.setEmergencyCloseFilled(snapshot.isEmergencyCloseFilled());
        dto.setProtectionTriggered(snapshot.isProtectionTriggered());
        dto.setEntryOrderStatus(snapshot.getEntryOrderStatus());
        dto.setStopLossStatus(snapshot.getStopLossStatus());
        dto.setTakeProfitStatus(snapshot.getTakeProfitStatus());
        dto.setEmergencyCloseStatus(snapshot.getEmergencyCloseStatus());
        dto.setPositionQuantity(snapshot.getPositionQuantity());
        dto.setActualFilledQty(snapshot.getActualFilledQty());
        dto.setAvgFillPrice(snapshot.getAvgFillPrice());
        dto.setEntryPrice(snapshot.getEntryPrice());
        dto.setMarkPrice(snapshot.getMarkPrice());
        dto.setRealizedGrossPnlUsdt(snapshot.getRealizedGrossPnlUsdt());
        dto.setRealizedFeesUsdt(snapshot.getRealizedFeesUsdt());
        dto.setRealizedNetPnlUsdt(snapshot.getRealizedNetPnlUsdt());
        dto.setUnrealizedPnlUsdt(snapshot.getUnrealizedPnlUsdt());
        dto.setLastSuccessfulSyncAt(snapshot.getLastSuccessfulSyncAt());
        dto.setSyncCompletedAt(snapshot.getSyncCompletedAt());
        dto.setSnapshot(readJson(snapshot.getSnapshotJson()));
        return dto;
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
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record SyncGateDecision(
            String reasonCode,
            String reasonMessage,
            BudgetTargetSyncHealthDTO syncHealth) {
    }

    public record RecordedSyncSnapshot(
            LiveTradeExecution execution,
            String syncType,
            String syncStatus,
            String traceId,
            String errorCode,
            String errorMessage,
            boolean divergenceDetected,
            boolean requiresIntervention,
            boolean openPosition,
            int activeOpenOrderCount,
            int activeProtectionOrderCount,
            boolean stopLossActive,
            boolean takeProfitActive,
            boolean emergencyCloseWorking,
            boolean emergencyCloseFilled,
            boolean protectionTriggered,
            String entryOrderStatus,
            String stopLossStatus,
            String takeProfitStatus,
            String emergencyCloseStatus,
            BigDecimal positionQuantity,
            BigDecimal actualFilledQty,
            BigDecimal avgFillPrice,
            BigDecimal entryPrice,
            BigDecimal markPrice,
            BigDecimal realizedGrossPnlUsdt,
            BigDecimal realizedFeesUsdt,
            BigDecimal realizedNetPnlUsdt,
            BigDecimal unrealizedPnlUsdt,
            Instant syncCompletedAt,
            Map<String, Object> snapshotPayload) {
    }
}
