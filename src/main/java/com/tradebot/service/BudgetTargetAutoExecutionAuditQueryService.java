package com.tradebot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.dto.BudgetTargetCriticalErrorDTO;
import com.tradebot.dto.BudgetTargetEventTimelineItemDTO;
import com.tradebot.dto.BudgetTargetEventTimelineResponseDTO;
import com.tradebot.dto.BudgetTargetPnlLedgerEntryDTO;
import com.tradebot.dto.BudgetTargetSessionAuditReplayDTO;
import com.tradebot.dto.BudgetTargetSessionConfigSnapshotDTO;
import com.tradebot.dto.BudgetTargetSessionDetailDTO;
import com.tradebot.dto.BudgetTargetSessionSummaryDTO;
import com.tradebot.dto.BudgetTargetTradeClosureDTO;
import com.tradebot.dto.BudgetTargetTradeDetailDTO;
import com.tradebot.dto.BudgetTargetTradeHistoryItemDTO;
import com.tradebot.dto.BudgetTargetTradeHistoryResponseDTO;
import com.tradebot.dto.BudgetTargetTradeOrderDTO;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.entity.BudgetTargetAuditSeverity;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionEvent;
import com.tradebot.entity.LiveTradeClosure;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionEvent;
import com.tradebot.entity.LiveTradeOrder;
import com.tradebot.entity.LiveTradePnlLedger;
import com.tradebot.entity.SessionSymbolDecisionAudit;
import com.tradebot.repository.BudgetTargetSessionEventRepository;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.repository.LiveTradeClosureRepository;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.LiveTradeOrderRepository;
import com.tradebot.repository.LiveTradePnlLedgerRepository;
import com.tradebot.repository.SessionSymbolDecisionAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BudgetTargetAutoExecutionAuditQueryService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final BudgetTargetSessionRepository sessionRepository;
    private final BudgetTargetSessionEventRepository sessionEventRepository;
    private final SessionSymbolDecisionAuditRepository decisionAuditRepository;
    private final LiveTradeExecutionRepository executionRepository;
    private final LiveTradeExecutionEventRepository executionEventRepository;
    private final LiveTradeOrderRepository orderRepository;
    private final LiveTradeClosureRepository closureRepository;
    private final LiveTradePnlLedgerRepository pnlLedgerRepository;
    private final LiveTradingMapper liveTradingMapper;
    private final ExchangeSyncSnapshotService exchangeSyncSnapshotService;
    private final ObjectMapper objectMapper;

    public List<BudgetTargetSessionSummaryDTO> listSessions(int limit, int offset) {
        int normalizedLimit = normalizeLimit(limit, 20);
        int normalizedOffset = Math.max(offset, 0);
        return sessionRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(normalizedOffset / normalizedLimit, normalizedLimit))
                .stream()
                .skip(normalizedOffset % normalizedLimit)
                .limit(normalizedLimit)
                .map(this::buildSessionSummary)
                .toList();
    }

    public BudgetTargetSessionSummaryDTO getSessionSummary(UUID sessionId) {
        return buildSessionSummary(requireSession(sessionId));
    }

    public BudgetTargetSessionDetailDTO getSessionDetail(UUID sessionId) {
        BudgetTargetSession session = requireSession(sessionId);
        List<BudgetTargetEventTimelineItemDTO> timeline = loadTimeline(sessionId);
        BudgetTargetSessionDetailDTO dto = new BudgetTargetSessionDetailDTO();
        dto.setSummary(buildSessionSummary(session));
        dto.setConfigSnapshot(readConfigSnapshot(session.getConfigSnapshotJson()));
        dto.setPendingScanRunId(session.getPendingScanRunId());
        dto.setTraceId(session.getTraceId());
        dto.setLatestCriticalError(dto.getSummary().getMostRecentCriticalError());
        dto.setSyncHealth(dto.getSummary().getSyncHealth());
        dto.setLastEventAt(timeline.isEmpty() ? null : timeline.getFirst().getEventTs());
        dto.setTimelineEventCount(timeline.size());
        dto.setTradeCount(executionRepository.findBySession_IdOrderByCreatedAtDesc(sessionId).size());
        return dto;
    }

    public BudgetTargetEventTimelineResponseDTO getTimeline(UUID sessionId,
            int limit,
            int offset,
            String category,
            String severity,
            UUID executionId) {
        List<BudgetTargetEventTimelineItemDTO> timeline = loadTimeline(sessionId).stream()
                .filter(item -> category == null || category.isBlank() || category.equalsIgnoreCase(item.getEventCategory()))
                .filter(item -> severity == null || severity.isBlank() || severity.equalsIgnoreCase(item.getSeverity()))
                .filter(item -> executionId == null || Objects.equals(item.getExecutionId(), executionId))
                .toList();

        BudgetTargetEventTimelineResponseDTO response = new BudgetTargetEventTimelineResponseDTO();
        response.setTotal(loadTimeline(sessionId).size());
        response.setFilteredCount(timeline.size());
        response.setItems(page(timeline, limit, offset));
        return response;
    }

    public BudgetTargetTradeHistoryResponseDTO getTradeHistory(UUID sessionId, int limit, int offset, String state) {
        List<BudgetTargetTradeHistoryItemDTO> items = executionRepository.findBySession_IdOrderByCreatedAtDesc(sessionId).stream()
                .map(this::toTradeHistoryItem)
                .filter(item -> matchesState(item, state))
                .toList();

        BudgetTargetTradeHistoryResponseDTO response = new BudgetTargetTradeHistoryResponseDTO();
        response.setTotal(items.size());
        response.setActiveCount((int) items.stream().filter(this::isActiveTrade).count());
        response.setCompletedCount((int) items.stream().filter(item -> !isActiveTrade(item)).count());
        response.setItems(page(items, limit, offset));
        return response;
    }

    public BudgetTargetTradeDetailDTO getTradeDetail(UUID sessionId, UUID executionId) {
        LiveTradeExecution execution = executionRepository.findById(executionId)
                .filter(item -> item.getSession() != null && sessionId.equals(item.getSession().getId()))
                .orElseThrow(() -> new NoSuchElementException("Trade execution not found: " + executionId));

        LiveTradeExecutionDTO executionDto = liveTradingMapper.toDetail(
                execution,
                executionEventRepository.findByExecution_IdOrderByEventTsAsc(executionId));
        executionDto.setSyncHealth(exchangeSyncSnapshotService.summarizeExecution(executionId));

        BudgetTargetTradeDetailDTO dto = new BudgetTargetTradeDetailDTO();
        dto.setTrade(toTradeHistoryItem(execution));
        dto.setExecution(executionDto);
        dto.setDecisionAudits(decisionAuditRepository.findByExecution_IdOrderByEventTsAsc(executionId).stream()
                .map(this::toTimelineItem)
                .toList());
        dto.setOrders(orderRepository.findByExecution_IdOrderByCreatedAtAsc(executionId).stream()
                .map(this::toOrderDto)
                .toList());
        dto.setClosure(closureRepository.findByExecution_Id(executionId).map(this::toClosureDto).orElse(null));
        dto.setPnlLedgerEntries(pnlLedgerRepository.findByExecution_IdOrderByEventTsDesc(executionId).stream()
                .map(this::toPnlLedgerDto)
                .toList());
        dto.setSyncSnapshots(exchangeSyncSnapshotService.listSnapshots(executionId, 50));
        dto.setGeneratedAt(Instant.now());
        return dto;
    }

    public BudgetTargetSessionAuditReplayDTO getAuditReplay(UUID sessionId) {
        BudgetTargetSessionAuditReplayDTO dto = new BudgetTargetSessionAuditReplayDTO();
        dto.setSession(getSessionDetail(sessionId));

        List<BudgetTargetEventTimelineItemDTO> timeline = loadTimeline(sessionId).stream()
                .sorted(Comparator.comparing(BudgetTargetEventTimelineItemDTO::getEventTs,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        dto.setTimeline(timeline);

        LinkedHashMap<String, BudgetTargetTradeDetailDTO> trades = new LinkedHashMap<>();
        for (LiveTradeExecution execution : executionRepository.findBySession_IdOrderByCreatedAtDesc(sessionId)) {
            BudgetTargetTradeHistoryItemDTO item = toTradeHistoryItem(execution);
            if (item.getExecutionId() != null) {
                trades.put(item.getExecutionId().toString(), getTradeDetail(sessionId, item.getExecutionId()));
            }
        }
        dto.setTrades(trades);
        dto.setGeneratedAt(Instant.now());
        return dto;
    }

    public BudgetTargetEventTimelineItemDTO toTimelineItem(BudgetTargetSessionEvent event) {
        Map<String, Object> after = readJson(event.getAfterJson());
        BudgetTargetEventTimelineItemDTO dto = new BudgetTargetEventTimelineItemDTO();
        dto.setId("session:" + event.getId());
        dto.setSourceType("BUDGET_TARGET_SESSION_EVENT");
        dto.setEventCategory(event.getEventCategory().name());
        dto.setSeverity(event.getSeverity().name());
        dto.setEventTs(event.getEventTs());
        dto.setSessionId(event.getSession() != null ? event.getSession().getId() : null);
        dto.setExecutionId(event.getExecution() != null ? event.getExecution().getId() : null);
        dto.setEventType(event.getEventType());
        dto.setStatus(event.getEventStatus());
        dto.setReasonCode(event.getReasonCode());
        dto.setActor(event.getActor());
        dto.setMessage(event.getNotes());
        dto.setSummaryPayload(summaryPayload(after));
        dto.setDebugAvailable(hasDebugPayload(event.getBeforeJson(), event.getAfterJson()));
        fillDerivedIdentifiers(dto, after);
        return dto;
    }

    public BudgetTargetEventTimelineItemDTO toTimelineItem(SessionSymbolDecisionAudit audit) {
        Map<String, Object> after = readJson(audit.getAfterJson());
        BudgetTargetEventTimelineItemDTO dto = new BudgetTargetEventTimelineItemDTO();
        dto.setId("decision:" + audit.getId());
        dto.setSourceType("SESSION_SYMBOL_DECISION_AUDIT");
        dto.setEventCategory(audit.getEventCategory().name());
        dto.setSeverity(audit.getSeverity().name());
        dto.setEventTs(audit.getEventTs());
        dto.setSessionId(audit.getSession() != null ? audit.getSession().getId() : null);
        dto.setExecutionId(audit.getExecution() != null ? audit.getExecution().getId() : null);
        dto.setRecommendationId(audit.getRecommendation() != null ? audit.getRecommendation().getId() : null);
        dto.setScanRunId(audit.getScanRun() != null ? audit.getScanRun().getId() : null);
        dto.setSymbol(audit.getSymbol());
        dto.setEventType(audit.getEventType());
        dto.setStatus(extractStatus(after));
        dto.setReasonCode(extractString(after, "reasonCode"));
        dto.setActor(audit.getActor());
        dto.setMessage(audit.getNotes());
        dto.setSummaryPayload(summaryPayload(after));
        dto.setDebugAvailable(hasDebugPayload(audit.getBeforeJson(), audit.getAfterJson()));
        return dto;
    }

    public BudgetTargetEventTimelineItemDTO toTimelineItem(LiveTradeExecutionEvent event) {
        Map<String, Object> after = readJson(event.getAfterJson());
        BudgetTargetEventTimelineItemDTO dto = new BudgetTargetEventTimelineItemDTO();
        dto.setId("execution:" + event.getId());
        dto.setSourceType("LIVE_TRADE_EXECUTION_EVENT");
        dto.setEventCategory(event.getEventCategory().name());
        dto.setSeverity(event.getSeverity().name());
        dto.setEventTs(event.getEventTs());
        dto.setSessionId(event.getExecution() != null && event.getExecution().getSession() != null
                ? event.getExecution().getSession().getId()
                : null);
        dto.setExecutionId(event.getExecution() != null ? event.getExecution().getId() : null);
        dto.setRecommendationId(event.getExecution() != null && event.getExecution().getRecommendation() != null
                ? event.getExecution().getRecommendation().getId()
                : null);
        dto.setScanRunId(event.getExecution() != null
                && event.getExecution().getRecommendation() != null
                && event.getExecution().getRecommendation().getScanRun() != null
                ? event.getExecution().getRecommendation().getScanRun().getId()
                : null);
        dto.setSymbol(event.getExecution() != null ? event.getExecution().getSymbol() : null);
        dto.setEventType(event.getEventType());
        dto.setStatus(event.getEventStatus());
        dto.setReasonCode(event.getErrorCode());
        dto.setActor(event.getActor());
        dto.setMessage(event.getNotes());
        dto.setSummaryPayload(summaryPayload(after));
        dto.setDebugAvailable(hasDebugPayload(event.getBeforeJson(), event.getAfterJson()));
        return dto;
    }

    private BudgetTargetSessionSummaryDTO buildSessionSummary(BudgetTargetSession session) {
        List<BudgetTargetSessionEvent> sessionEvents = sessionEventRepository.findBySession_IdOrderByEventTsAsc(session.getId());
        List<LiveTradeExecution> executions = executionRepository.findBySession_IdOrderByCreatedAtDesc(session.getId());
        List<LiveTradeExecutionEvent> executionEvents = executionEventRepository.findByExecution_Session_IdOrderByEventTsDesc(session.getId());

        BudgetTargetSessionSummaryDTO dto = new BudgetTargetSessionSummaryDTO();
        dto.setId(session.getId());
        dto.setStatus(session.getStatus().name());
        dto.setStartedAt(session.getStartedAt());
        dto.setEndedAt(session.getEndedAt());
        dto.setStartedBy(session.getStartedBy());
        dto.setStartReason(resolveStartReason(sessionEvents));
        dto.setTargetSatisfiedAt(resolveTargetSatisfiedAt(sessionEvents));
        dto.setBudgetAmountUsdt(session.getBudgetAmountUsdt());
        dto.setTargetProfitUsdt(session.getTargetProfitUsdt());
        dto.setRealizedNetPnlUsdt(session.getRealizedNetPnlUsdt());
        dto.setTotalGrossPnlUsdt(sum(executions.stream().map(LiveTradeExecution::getRealizedGrossPnlUsdt).toList()));
        dto.setFeeTotalUsdt(sum(executions.stream()
                .map(LiveTradeExecution::getRealizedFeesUsdt)
                .map(value -> value == null ? null : value.abs())
                .toList()));
        dto.setWinCount((int) executions.stream().filter(this::isCompletedExecution).filter(item -> positive(item.getRealizedNetPnlUsdt())).count());
        dto.setLossCount((int) executions.stream().filter(this::isCompletedExecution).filter(item -> negative(item.getRealizedNetPnlUsdt())).count());
        dto.setActiveTradeCount((int) executions.stream().filter(item -> item.getExecutionState().isActive()).count());
        dto.setCompletedTradeCount((int) executions.stream().filter(this::isCompletedExecution).count());
        dto.setStopReason(session.getStopReason());
        dto.setMostRecentCriticalError(resolveMostRecentCriticalError(sessionEvents, executionEvents, executions));
        dto.setSyncHealth(exchangeSyncSnapshotService.summarizeSession(session.getId()));
        return dto;
    }

    private List<BudgetTargetEventTimelineItemDTO> loadTimeline(UUID sessionId) {
        List<BudgetTargetEventTimelineItemDTO> items = new ArrayList<>();
        items.addAll(sessionEventRepository.findBySession_IdOrderByEventTsDesc(sessionId).stream().map(this::toTimelineItem).toList());
        items.addAll(decisionAuditRepository.findBySession_IdOrderByEventTsDesc(sessionId).stream().map(this::toTimelineItem).toList());
        items.addAll(executionEventRepository.findByExecution_Session_IdOrderByEventTsDesc(sessionId).stream().map(this::toTimelineItem).toList());
        items.sort(Comparator.comparing(BudgetTargetEventTimelineItemDTO::getEventTs,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(BudgetTargetEventTimelineItemDTO::getId));
        return items;
    }

    private BudgetTargetTradeHistoryItemDTO toTradeHistoryItem(LiveTradeExecution execution) {
        BudgetTargetTradeHistoryItemDTO dto = new BudgetTargetTradeHistoryItemDTO();
        dto.setExecutionId(execution.getId());
        dto.setRecommendationId(execution.getRecommendation() != null ? execution.getRecommendation().getId() : null);
        dto.setScanRunId(execution.getRecommendation() != null && execution.getRecommendation().getScanRun() != null
                ? execution.getRecommendation().getScanRun().getId()
                : null);
        dto.setSymbol(execution.getSymbol());
        dto.setSide(execution.getSide());
        dto.setTriggerMode(execution.getTriggerMode() != null ? execution.getTriggerMode().name() : null);
        dto.setAllocatedBudgetSliceUsdt(execution.getRequestedBudgetSliceUsdt());
        dto.setReservedMarginUsdt(execution.getReservedMarginUsdt());
        dto.setPositionSlot(execution.getPositionSlot());
        dto.setOpenedAt(execution.getSubmittedAt() != null ? execution.getSubmittedAt() : execution.getCreatedAt());
        dto.setClosedAt(execution.getCompletedAt());
        dto.setExecutionState(execution.getExecutionState().name());
        dto.setOpenReason(resolveOpenReason(execution));
        dto.setCloseReason(resolveCloseReason(execution));
        dto.setRealizedGrossPnlUsdt(execution.getRealizedGrossPnlUsdt());
        dto.setRealizedFeesUsdt(execution.getRealizedFeesUsdt());
        dto.setRealizedNetPnlUsdt(execution.getRealizedNetPnlUsdt());
        dto.setOutcome(resolveOutcome(execution.getRealizedNetPnlUsdt()));
        dto.setLatestCriticalError(resolveTradeCriticalError(execution));
        return dto;
    }

    private BudgetTargetTradeOrderDTO toOrderDto(LiveTradeOrder order) {
        BudgetTargetTradeOrderDTO dto = new BudgetTargetTradeOrderDTO();
        dto.setId(order.getId());
        dto.setOrderRole(order.getOrderRole());
        dto.setClientOrderId(order.getClientOrderId());
        dto.setExchangeOrderId(order.getExchangeOrderId());
        dto.setClientAlgoId(order.getClientAlgoId());
        dto.setExchangeAlgoId(order.getExchangeAlgoId());
        dto.setRequestedQty(order.getRequestedQty());
        dto.setExecutedQty(order.getExecutedQty());
        dto.setLimitPrice(order.getLimitPrice());
        dto.setTriggerPrice(order.getTriggerPrice());
        dto.setAvgFillPrice(order.getAvgFillPrice());
        dto.setOrderStatus(order.getOrderStatus());
        dto.setRequestPayload(readJson(order.getRequestJson()));
        dto.setResponsePayload(readJson(order.getResponseJson()));
        dto.setSnapshotPayload(readJson(order.getSnapshotJson()));
        dto.setCreatedAt(order.getCreatedAt());
        dto.setUpdatedAt(order.getUpdatedAt());
        return dto;
    }

    private BudgetTargetTradeClosureDTO toClosureDto(LiveTradeClosure closure) {
        BudgetTargetTradeClosureDTO dto = new BudgetTargetTradeClosureDTO();
        dto.setId(closure.getId());
        dto.setCloseReason(closure.getCloseReason());
        dto.setClosedQty(closure.getClosedQty());
        dto.setClosedPrice(closure.getClosedPrice());
        dto.setClosingClientOrderId(closure.getClosingClientOrderId());
        dto.setClosingOrderId(closure.getClosingOrderId());
        dto.setFinalPositionSnapshot(readJson(closure.getFinalPositionSnapshotJson()));
        dto.setCloseResponse(readJson(closure.getCloseResponseJson()));
        dto.setClosedAt(closure.getClosedAt());
        return dto;
    }

    private BudgetTargetPnlLedgerEntryDTO toPnlLedgerDto(LiveTradePnlLedger entry) {
        BudgetTargetPnlLedgerEntryDTO dto = new BudgetTargetPnlLedgerEntryDTO();
        dto.setId(entry.getId());
        dto.setEventType(entry.getEventType());
        dto.setAmountUsdt(entry.getAmountUsdt());
        dto.setEventTs(entry.getEventTs());
        dto.setSourceType(entry.getSourceType());
        dto.setSourceRef(entry.getSourceRef());
        dto.setNotes(entry.getNotes());
        dto.setBefore(readJson(entry.getBeforeJson()));
        dto.setAfter(readJson(entry.getAfterJson()));
        return dto;
    }

    private BudgetTargetCriticalErrorDTO resolveMostRecentCriticalError(List<BudgetTargetSessionEvent> sessionEvents,
            List<LiveTradeExecutionEvent> executionEvents,
            List<LiveTradeExecution> executions) {
        BudgetTargetCriticalErrorDTO fromEvents = sessionEvents.stream()
                .filter(event -> event.getSeverity() == BudgetTargetAuditSeverity.ERROR)
                .max(Comparator.comparing(BudgetTargetSessionEvent::getEventTs, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toCriticalError)
                .orElse(executionEvents.stream()
                        .filter(event -> event.getSeverity() == BudgetTargetAuditSeverity.ERROR)
                        .max(Comparator.comparing(LiveTradeExecutionEvent::getEventTs, Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(this::toCriticalError)
                        .orElse(null));

        BudgetTargetCriticalErrorDTO fromExecution = executions.stream()
                .map(this::criticalIssueFromExecution)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(BudgetTargetCriticalErrorDTO::getEventTs,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null);

        if (fromEvents == null) {
            return fromExecution;
        }
        if (fromExecution == null) {
            return fromEvents;
        }
        if (fromEvents.getEventTs() != null
                && fromExecution.getEventTs() != null
                && fromExecution.getEventTs().isAfter(fromEvents.getEventTs())) {
            return fromExecution;
        }
        return fromEvents;
    }

    private BudgetTargetCriticalErrorDTO resolveTradeCriticalError(LiveTradeExecution execution) {
        BudgetTargetCriticalErrorDTO fromEvents = executionEventRepository.findByExecution_IdOrderByEventTsAsc(execution.getId()).stream()
                .filter(event -> event.getSeverity() == BudgetTargetAuditSeverity.ERROR)
                .max(Comparator.comparing(LiveTradeExecutionEvent::getEventTs))
                .map(this::toCriticalError)
                .orElse(null);
        BudgetTargetCriticalErrorDTO fromExecution = criticalIssueFromExecution(execution);
        if (fromEvents == null) {
            return fromExecution;
        }
        if (fromExecution == null) {
            return fromEvents;
        }
        if (fromExecution.getEventTs() != null
                && fromEvents.getEventTs() != null
                && fromExecution.getEventTs().isAfter(fromEvents.getEventTs())) {
            return fromExecution;
        }
        return fromEvents;
    }

    private BudgetTargetCriticalErrorDTO toCriticalError(BudgetTargetSessionEvent event) {
        BudgetTargetCriticalErrorDTO dto = new BudgetTargetCriticalErrorDTO();
        dto.setSourceType("BUDGET_TARGET_SESSION_EVENT");
        dto.setEventCategory(event.getEventCategory().name());
        dto.setEventType(event.getEventType());
        dto.setCode(event.getReasonCode());
        dto.setMessage(event.getNotes());
        dto.setEventTs(event.getEventTs());
        dto.setExecutionId(event.getExecution() != null ? event.getExecution().getId() : null);
        return dto;
    }

    private BudgetTargetCriticalErrorDTO toCriticalError(LiveTradeExecutionEvent event) {
        BudgetTargetCriticalErrorDTO dto = new BudgetTargetCriticalErrorDTO();
        dto.setSourceType("LIVE_TRADE_EXECUTION_EVENT");
        dto.setEventCategory(event.getEventCategory().name());
        dto.setEventType(event.getEventType());
        dto.setCode(event.getErrorCode());
        dto.setMessage(event.getNotes());
        dto.setEventTs(event.getEventTs());
        dto.setExecutionId(event.getExecution() != null ? event.getExecution().getId() : null);
        return dto;
    }

    private BudgetTargetCriticalErrorDTO criticalIssueFromExecution(LiveTradeExecution execution) {
        Map<String, Object> issue = readJson(execution.getCriticalIssueJson());
        if (issue.isEmpty()) {
            return null;
        }
        BudgetTargetCriticalErrorDTO dto = new BudgetTargetCriticalErrorDTO();
        dto.setSourceType("LIVE_TRADE_EXECUTION");
        dto.setEventCategory("EXCHANGE");
        dto.setEventType("CRITICAL_ISSUE");
        dto.setCode(asString(issue.get("code")));
        dto.setMessage(asString(issue.get("message")));
        dto.setEventTs(parseInstant(issue.get("raisedAt")));
        dto.setExecutionId(execution.getId());
        return dto;
    }

    private BudgetTargetSessionConfigSnapshotDTO readConfigSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return new BudgetTargetSessionConfigSnapshotDTO();
        }
        try {
            return objectMapper.convertValue(objectMapper.readValue(json, MAP_TYPE), BudgetTargetSessionConfigSnapshotDTO.class);
        } catch (Exception ex) {
            return new BudgetTargetSessionConfigSnapshotDTO();
        }
    }

    private String resolveStartReason(List<BudgetTargetSessionEvent> events) {
        return events.stream()
                .filter(event -> "SESSION_DRAFTED".equalsIgnoreCase(event.getEventType()))
                .map(BudgetTargetSessionEvent::getAfterJson)
                .map(this::readJson)
                .map(this::summaryPayload)
                .map(payload -> asString(payload.get("startReason")))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private Instant resolveTargetSatisfiedAt(List<BudgetTargetSessionEvent> events) {
        return events.stream()
                .filter(event -> "TARGET_REACHED".equalsIgnoreCase(event.getEventType()))
                .map(this::extractTargetSatisfiedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private Instant extractTargetSatisfiedAt(BudgetTargetSessionEvent event) {
        Map<String, Object> after = readJson(event.getAfterJson());
        Map<String, Object> payload = summaryPayload(after);
        Instant fromPayload = parseInstant(payload.get("targetSatisfiedAt"));
        return fromPayload != null ? fromPayload : event.getEventTs();
    }

    private String resolveOpenReason(LiveTradeExecution execution) {
        return decisionAuditRepository.findByExecution_IdOrderByEventTsAsc(execution.getId()).stream()
                .filter(item -> "INTAKE_ACCEPTED".equalsIgnoreCase(item.getEventType()))
                .map(SessionSymbolDecisionAudit::getNotes)
                .findFirst()
                .orElseGet(() -> {
                    Map<String, Object> payload = liveTradingMapper.readJson(execution.getPayloadSnapshotJson());
                    Map<String, Object> request = extractMap(payload, "request");
                    return asString(request.get("operatorNote"));
                });
    }

    private String resolveCloseReason(LiveTradeExecution execution) {
        return closureRepository.findByExecution_Id(execution.getId())
                .map(LiveTradeClosure::getCloseReason)
                .orElse(execution.getCloseReason());
    }

    private String resolveOutcome(BigDecimal realizedNetPnl) {
        if (positive(realizedNetPnl)) {
            return "WIN";
        }
        if (negative(realizedNetPnl)) {
            return "LOSS";
        }
        return "BREAKEVEN";
    }

    private boolean matchesState(BudgetTargetTradeHistoryItemDTO item, String state) {
        if (state == null || state.isBlank()) {
            return true;
        }
        return switch (state.toUpperCase()) {
            case "ACTIVE" -> isActiveTrade(item);
            case "COMPLETED" -> !isActiveTrade(item);
            default -> true;
        };
    }

    private boolean isActiveTrade(BudgetTargetTradeHistoryItemDTO item) {
        return item.getExecutionState() != null
                && !List.of("CLOSED", "FAILED", "PREFLIGHT_REJECTED").contains(item.getExecutionState().toUpperCase());
    }

    private boolean isCompletedExecution(LiveTradeExecution execution) {
        return execution.getExecutionState() != null && execution.getExecutionState().isTerminal();
    }

    private <T> List<T> page(List<T> items, int limit, int offset) {
        int normalizedLimit = normalizeLimit(limit, 50);
        int normalizedOffset = Math.max(offset, 0);
        if (normalizedOffset >= items.size()) {
            return List.of();
        }
        return items.subList(normalizedOffset, Math.min(items.size(), normalizedOffset + normalizedLimit));
    }

    private int normalizeLimit(int limit, int fallback) {
        if (limit <= 0) {
            return fallback;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
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

    private BudgetTargetSession requireSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Budget target session not found: " + sessionId));
    }

    private Map<String, Object> summaryPayload(Map<String, Object> after) {
        if (after.isEmpty()) {
            return Map.of();
        }
        Object payload = after.get("payload");
        if (payload instanceof Map<?, ?> payloadMap) {
            LinkedHashMap<String, Object> compact = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : payloadMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    compact.put(key, compactValue(entry.getValue()));
                }
            }
            return compact;
        }
        return after;
    }

    private Object compactValue(Object value) {
        if (value instanceof LiveTradeExecutionDTO execution) {
            return Map.of(
                    "id", execution.getId(),
                    "executionState", execution.getExecutionState(),
                    "errorCode", execution.getErrorCode(),
                    "errorMessage", execution.getErrorMessage());
        }
        if (value instanceof Map<?, ?> valueMap) {
            LinkedHashMap<String, Object> compact = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : valueMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    compact.put(key, entry.getValue() instanceof Map<?, ?> nested ? compactMap(nested) : entry.getValue());
                }
            }
            return compact;
        }
        return value;
    }

    private Map<String, Object> compactMap(Map<?, ?> valueMap) {
        LinkedHashMap<String, Object> compact = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : valueMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> nested) {
                    compact.put(key, compactMap(nested));
                } else {
                    compact.put(key, value);
                }
            }
        }
        return compact;
    }

    private void fillDerivedIdentifiers(BudgetTargetEventTimelineItemDTO dto, Map<String, Object> after) {
        if (dto.getRecommendationId() == null) {
            dto.setRecommendationId(parseUuid(summaryPayload(after).get("recommendationId")));
        }
        if (dto.getScanRunId() == null) {
            dto.setScanRunId(parseUuid(summaryPayload(after).get("scanRunId")));
        }
        if (dto.getSymbol() == null) {
            dto.setSymbol(asString(summaryPayload(after).get("symbol")));
        }
    }

    private String extractStatus(Map<String, Object> after) {
        Object session = after.get("session");
        if (session instanceof Map<?, ?> sessionMap) {
            return asString(sessionMap.get("status"));
        }
        return asString(after.get("status"));
    }

    private String extractString(Map<String, Object> after, String key) {
        Object value = after.get(key);
        if (value instanceof String text) {
            return text;
        }
        Object payload = after.get("payload");
        if (payload instanceof Map<?, ?> payloadMap) {
            Object nested = payloadMap.get(key);
            if (nested instanceof String text) {
                return text;
            }
        }
        return null;
    }

    private Map<String, Object> extractMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> map) {
            return compactMap(map);
        }
        return Map.of();
    }

    private boolean hasDebugPayload(String beforeJson, String afterJson) {
        return (beforeJson != null && !beforeJson.isBlank()) || (afterJson != null && !afterJson.isBlank());
    }

    private BigDecimal sum(List<BigDecimal> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean negative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) < 0;
    }

    private String asString(Object value) {
        return value instanceof String text ? text : null;
    }

    private UUID parseUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private Instant parseInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Instant.parse(text);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }
}
