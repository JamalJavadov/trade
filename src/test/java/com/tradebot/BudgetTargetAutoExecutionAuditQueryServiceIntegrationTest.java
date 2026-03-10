package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.dto.BudgetTargetEventTimelineResponseDTO;
import com.tradebot.dto.BudgetTargetEventTimelineItemDTO;
import com.tradebot.dto.BudgetTargetSessionAuditReplayDTO;
import com.tradebot.dto.BudgetTargetSessionDetailDTO;
import com.tradebot.dto.BudgetTargetSessionSummaryDTO;
import com.tradebot.dto.BudgetTargetTradeDetailDTO;
import com.tradebot.dto.BudgetTargetTradeHistoryResponseDTO;
import com.tradebot.entity.BudgetTargetAuditEventCategory;
import com.tradebot.entity.BudgetTargetAuditSeverity;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionEvent;
import com.tradebot.entity.BudgetTargetSessionStatus;
import com.tradebot.entity.ExchangeSyncSnapshot;
import com.tradebot.entity.LiveTradeClosure;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionEvent;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.LiveTradeOrder;
import com.tradebot.entity.LiveTradePnlLedger;
import com.tradebot.entity.LiveTradeTriggerMode;
import com.tradebot.entity.Recommendation;
import com.tradebot.entity.ScanRun;
import com.tradebot.entity.SessionSymbolDecisionAudit;
import com.tradebot.repository.BudgetTargetSessionEventRepository;
import com.tradebot.repository.ExchangeSyncSnapshotRepository;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.repository.LiveTradeClosureRepository;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.LiveTradeOrderRepository;
import com.tradebot.repository.LiveTradePnlLedgerRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.ScanRunRepository;
import com.tradebot.repository.SessionSymbolDecisionAuditRepository;
import com.tradebot.service.BudgetTargetAutoExecutionAuditQueryService;
import com.tradebot.service.ExchangeSyncSnapshotService;
import com.tradebot.service.LiveTradingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BudgetTargetAutoExecutionAuditQueryServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tradebot_audit_query")
            .withUsername("tradebot")
            .withPassword("tradebot");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    private BudgetTargetSessionRepository sessionRepository;

    @Autowired
    private BudgetTargetSessionEventRepository sessionEventRepository;

    @Autowired
    private LiveTradeExecutionRepository executionRepository;

    @Autowired
    private LiveTradeExecutionEventRepository executionEventRepository;

    @Autowired
    private LiveTradeOrderRepository orderRepository;

    @Autowired
    private LiveTradeClosureRepository closureRepository;

    @Autowired
    private LiveTradePnlLedgerRepository pnlLedgerRepository;

    @Autowired
    private SessionSymbolDecisionAuditRepository decisionAuditRepository;

    @Autowired
    private ScanRunRepository scanRunRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    @Autowired
    private ExchangeSyncSnapshotRepository exchangeSyncSnapshotRepository;

    private BudgetTargetAutoExecutionAuditQueryService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpService() {
        service = new BudgetTargetAutoExecutionAuditQueryService(
                sessionRepository,
                sessionEventRepository,
                decisionAuditRepository,
                executionRepository,
                executionEventRepository,
                orderRepository,
                closureRepository,
                pnlLedgerRepository,
                new LiveTradingMapper(objectMapper),
                new ExchangeSyncSnapshotService(exchangeSyncSnapshotRepository, executionRepository, objectMapper),
                objectMapper);
    }

    @Test
    void buildsAuditableSessionReportTradeHistoryTimelineAndReplay() throws Exception {
        Instant draftedAt = Instant.parse("2026-03-09T00:00:00Z");
        Instant targetSatisfiedAt = Instant.parse("2026-03-09T00:15:00Z");
        Instant exchangeFailureAt = Instant.parse("2026-03-09T00:16:00Z");

        ScanRun scanRun = new ScanRun();
        scanRun.setStartedAt(draftedAt);
        scanRun.setRequestedAt(draftedAt);
        scanRun.setIntervalMinutes(20);
        scanRun.setTopN(20);
        scanRun.setStatus("FINISHED");
        scanRun.setTriggerType("AUTO_SESSION");
        scanRun = scanRunRepository.save(scanRun);

        Recommendation winRecommendation = recommendation("BTCUSDT", scanRun);
        Recommendation lossRecommendation = recommendation("ETHUSDT", scanRun);

        BudgetTargetSession session = new BudgetTargetSession();
        session.setStatus(BudgetTargetSessionStatus.STOPPED);
        session.setBudgetAmountUsdt(new BigDecimal("50"));
        session.setTargetProfitUsdt(new BigDecimal("10"));
        session.setRealizedNetPnlUsdt(new BigDecimal("4.50"));
        session.setUnrealizedNetPnlUsdt(BigDecimal.ZERO);
        session.setMaxConcurrentPositions(3);
        session.setActivePositionsCount(0);
        session.setOpenedPositionsTotal(2);
        session.setClosedPositionsTotal(2);
        session.setStartedBy("operator-1");
        session.setStopReason("TARGET_REACHED");
        session.setTraceId("trace-session");
        session.setStartedAt(draftedAt);
        session.setEndedAt(targetSatisfiedAt.plusSeconds(90));
        session.setCreatedAt(draftedAt);
        session.setUpdatedAt(targetSatisfiedAt.plusSeconds(90));
        session.setConfigSnapshotJson(objectMapper.writeValueAsString(Map.of(
                "controlCenterVersion", 12,
                "sessionBudgetUsdt", "50",
                "targetProfitUsdt", "10",
                "maxConcurrentPositions", 3,
                "autoTargetMode", Map.of(
                        "armed", false,
                        "allowNewSessionStart", true,
                        "allowCloseAllOnTarget", true,
                        "requireBinanceHealthPass", true,
                        "requireOperatorConfirmationForStop", true,
                        "sessionTimeoutMinutes", 240))));
        session = sessionRepository.save(session);

        LiveTradeExecution winExecution = execution(session, winRecommendation, "BTCUSDT", 1,
                new BigDecimal("16.67"), new BigDecimal("15.00"),
                new BigDecimal("8.00"), new BigDecimal("1.00"), new BigDecimal("7.00"),
                "TAKE_PROFIT", draftedAt.plusSeconds(120), targetSatisfiedAt.plusSeconds(10));
        LiveTradeExecution lossExecution = execution(session, lossRecommendation, "ETHUSDT", 2,
                new BigDecimal("16.67"), new BigDecimal("15.00"),
                new BigDecimal("-2.00"), new BigDecimal("0.50"), new BigDecimal("-2.50"),
                "STOP_LOSS", draftedAt.plusSeconds(180), targetSatisfiedAt.plusSeconds(30));

        sessionEventRepository.save(sessionEvent(
                session,
                winExecution,
                "SESSION_DRAFTED",
                "DRAFT",
                "Session drafted from operator request.",
                null,
                draftedAt,
                "operator-1",
                BudgetTargetAuditSeverity.INFO,
                Map.of("session", Map.of("status", "DRAFT")),
                Map.of("payload", Map.of("startReason", "operator-start", "symbol", "BTCUSDT"))));
        sessionEventRepository.save(sessionEvent(
                session,
                null,
                "TARGET_REACHED",
                "STOPPED",
                "Final target realized net PnL was reached.",
                "TARGET_REACHED",
                targetSatisfiedAt,
                "system",
                BudgetTargetAuditSeverity.INFO,
                Map.of("session", Map.of("status", "RUNNING")),
                Map.of("payload", Map.of(
                        "targetSatisfiedAt", targetSatisfiedAt.toString(),
                        "realizedNetPnlUsdt", "4.50"))));

        decisionAuditRepository.save(decisionAudit(
                session,
                scanRun,
                winRecommendation,
                winExecution,
                "BTCUSDT",
                "BUDGET_ALLOCATED",
                draftedAt.plusSeconds(125),
                BudgetTargetAuditSeverity.INFO,
                "Allocated budget slice for BTC trade.",
                Map.of("symbol", "BTCUSDT"),
                Map.of("payload", Map.of(
                        "allocatedBudgetSliceUsdt", "16.67",
                        "positionSlot", 1,
                        "reasonCode", "BUDGET_ALLOCATED"))));
        decisionAuditRepository.save(decisionAudit(
                session,
                scanRun,
                winRecommendation,
                winExecution,
                "BTCUSDT",
                "INTAKE_ACCEPTED",
                draftedAt.plusSeconds(126),
                BudgetTargetAuditSeverity.INFO,
                "Opened after BTC intake passed.",
                Map.of("symbol", "BTCUSDT"),
                Map.of("payload", Map.of("reasonCode", "INTAKE_ACCEPTED"))));
        decisionAuditRepository.save(decisionAudit(
                session,
                scanRun,
                lossRecommendation,
                lossExecution,
                "ETHUSDT",
                "INTAKE_REJECTED",
                draftedAt.plusSeconds(181),
                BudgetTargetAuditSeverity.WARN,
                "Rejected stale ETH candidate.",
                Map.of("symbol", "ETHUSDT"),
                Map.of("payload", Map.of("reasonCode", "RECOMMENDATION_STALE"))));

        executionEventRepository.save(executionEvent(
                winExecution,
                "ENTRY_SUBMITTED",
                "ENTRY_SUBMITTED",
                null,
                draftedAt.plusSeconds(127),
                BudgetTargetAuditEventCategory.EXCHANGE,
                BudgetTargetAuditSeverity.INFO,
                "Entry submitted to Binance.",
                Map.of("state", "ENTRY_SUBMITTING"),
                Map.of("payload", Map.of("exchangeOrderId", 1001L, "status", "FILLED"))));
        executionEventRepository.save(executionEvent(
                lossExecution,
                "SAFE_CLOSE_TIMEOUT",
                "RECONCILING",
                "UPSTREAM_TIMEOUT",
                exchangeFailureAt,
                BudgetTargetAuditEventCategory.EXCHANGE,
                BudgetTargetAuditSeverity.ERROR,
                "Emergency close timed out.",
                Map.of("state", "CLOSING"),
                Map.of("payload", Map.of("reasonCode", "UPSTREAM_TIMEOUT", "message", "Timed out"))));

        orderRepository.save(order(winExecution, session, "ENTRY", "btc-entry", 1001L));
        orderRepository.save(order(lossExecution, session, "ENTRY", "eth-entry", 1002L));

        closureRepository.save(closure(winExecution, session, "TAKE_PROFIT", targetSatisfiedAt.plusSeconds(10)));
        closureRepository.save(closure(lossExecution, session, "STOP_LOSS", targetSatisfiedAt.plusSeconds(30)));

        exchangeSyncSnapshotRepository.save(syncSnapshot(
                session,
                winExecution,
                "SUCCESS",
                false,
                false,
                true,
                2,
                targetSatisfiedAt.plusSeconds(10),
                targetSatisfiedAt.plusSeconds(10),
                null,
                null));
        exchangeSyncSnapshotRepository.save(syncSnapshot(
                session,
                lossExecution,
                "FAILURE",
                false,
                true,
                false,
                0,
                exchangeFailureAt,
                targetSatisfiedAt.plusSeconds(10),
                "UPSTREAM_TIMEOUT",
                "Emergency close timed out."));

        pnlLedgerRepository.save(ledger(session, winExecution, "REALIZED_GROSS_PNL", new BigDecimal("8.00"), "gross:btc", targetSatisfiedAt.plusSeconds(10)));
        pnlLedgerRepository.save(ledger(session, winExecution, "REALIZED_FEES", new BigDecimal("-1.00"), "fees:btc", targetSatisfiedAt.plusSeconds(11)));
        pnlLedgerRepository.save(ledger(session, lossExecution, "REALIZED_GROSS_PNL", new BigDecimal("-2.00"), "gross:eth", targetSatisfiedAt.plusSeconds(30)));
        pnlLedgerRepository.save(ledger(session, lossExecution, "REALIZED_FEES", new BigDecimal("-0.50"), "fees:eth", targetSatisfiedAt.plusSeconds(31)));

        BudgetTargetSessionSummaryDTO summary = service.getSessionSummary(session.getId());
        BudgetTargetSessionDetailDTO detail = service.getSessionDetail(session.getId());
        BudgetTargetEventTimelineResponseDTO timeline = service.getTimeline(session.getId(), 20, 0, null, null, null);
        BudgetTargetTradeHistoryResponseDTO tradeHistory = service.getTradeHistory(session.getId(), 20, 0, null);
        BudgetTargetTradeDetailDTO tradeDetail = service.getTradeDetail(session.getId(), winExecution.getId());
        BudgetTargetSessionAuditReplayDTO replay = service.getAuditReplay(session.getId());

        assertThat(summary.getStartReason()).isEqualTo("operator-start");
        assertThat(summary.getTargetSatisfiedAt()).isEqualTo(targetSatisfiedAt);
        assertThat(summary.getRealizedNetPnlUsdt()).isEqualByComparingTo("4.50");
        assertThat(summary.getTotalGrossPnlUsdt()).isEqualByComparingTo("6.00");
        assertThat(summary.getFeeTotalUsdt()).isEqualByComparingTo("1.50");
        assertThat(summary.getWinCount()).isEqualTo(1);
        assertThat(summary.getLossCount()).isEqualTo(1);
        assertThat(summary.getActiveTradeCount()).isEqualTo(0);
        assertThat(summary.getCompletedTradeCount()).isEqualTo(2);
        assertThat(summary.getStopReason()).isEqualTo("TARGET_REACHED");
        assertThat(summary.getMostRecentCriticalError()).isNotNull();
        assertThat(summary.getMostRecentCriticalError().getCode()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(summary.getMostRecentCriticalError().getEventTs()).isEqualTo(exchangeFailureAt);
        assertThat(summary.getSyncHealth()).isNotNull();
        assertThat(summary.getSyncHealth().getStatus()).isEqualTo("FAILED");
        assertThat(summary.getSyncHealth().getLatestErrorCode()).isEqualTo("UPSTREAM_TIMEOUT");

        assertThat(detail.getConfigSnapshot().getSessionBudgetUsdt()).isEqualByComparingTo("50");
        assertThat(detail.getSyncHealth()).isNotNull();
        assertThat(detail.getSyncHealth().getStatus()).isEqualTo("FAILED");
        assertThat(detail.getTimelineEventCount()).isEqualTo(7);
        assertThat(detail.getTradeCount()).isEqualTo(2);
        assertThat(detail.getLastEventAt()).isEqualTo(exchangeFailureAt);

        assertThat(timeline.getTotal()).isEqualTo(7);
        assertThat(timeline.getItems().getFirst().getEventType()).isEqualTo("SAFE_CLOSE_TIMEOUT");
        assertThat(timeline.getItems().getFirst().getEventCategory()).isEqualTo("EXCHANGE");
        assertThat(timeline.getItems().getFirst().getSeverity()).isEqualTo("ERROR");
        assertThat(timeline.getItems().getFirst().isDebugAvailable()).isTrue();

        assertThat(tradeHistory.getTotal()).isEqualTo(2);
        assertThat(tradeHistory.getCompletedCount()).isEqualTo(2);
        assertThat(tradeHistory.getActiveCount()).isEqualTo(0);
        assertThat(tradeHistory.getItems())
                .extracting(item -> item.getExecutionId().toString(), item -> item.getOutcome(), item -> item.getCloseReason())
                .contains(
                        org.assertj.core.groups.Tuple.tuple(winExecution.getId().toString(), "WIN", "TAKE_PROFIT"),
                        org.assertj.core.groups.Tuple.tuple(lossExecution.getId().toString(), "LOSS", "STOP_LOSS"));
        assertThat(tradeHistory.getItems().stream()
                .filter(item -> item.getExecutionId().equals(winExecution.getId()))
                .findFirst()
                .orElseThrow()
                .getOpenReason()).isEqualTo("Opened after BTC intake passed.");

        assertThat(tradeDetail.getTrade()).isNotNull();
        assertThat(tradeDetail.getTrade().getAllocatedBudgetSliceUsdt()).isEqualByComparingTo("16.67");
        assertThat(tradeDetail.getOrders()).hasSize(1);
        assertThat(tradeDetail.getClosure()).isNotNull();
        assertThat(tradeDetail.getClosure().getCloseReason()).isEqualTo("TAKE_PROFIT");
        assertThat(tradeDetail.getPnlLedgerEntries()).hasSize(2);
        assertThat(tradeDetail.getDecisionAudits()).hasSize(2);
        assertThat(tradeDetail.getExecution()).isNotNull();
        assertThat(tradeDetail.getExecution().getSyncHealth()).isNotNull();
        assertThat(tradeDetail.getExecution().getSyncHealth().getStatus()).isEqualTo("HEALTHY");
        assertThat(tradeDetail.getSyncSnapshots()).hasSize(1);

        assertThat(replay.getTimeline()).hasSize(7);
        assertThat(replay.getTrades()).hasSize(2);
        assertThat(replay.getTrades()).containsKeys(winExecution.getId().toString(), lossExecution.getId().toString());
    }

    @Test
    void timelineItemSummaryPayloadPreservesNullFieldsForStopRequestedEvents() throws Exception {
        Instant now = Instant.parse("2026-03-09T01:00:00Z");

        BudgetTargetSession session = new BudgetTargetSession();
        session.setStatus(BudgetTargetSessionStatus.STOPPING);
        session.setBudgetAmountUsdt(new BigDecimal("150"));
        session.setTargetProfitUsdt(new BigDecimal("25"));
        session.setRealizedNetPnlUsdt(new BigDecimal("26"));
        session.setUnrealizedNetPnlUsdt(BigDecimal.ZERO);
        session.setMaxConcurrentPositions(3);
        session.setActivePositionsCount(3);
        session.setOpenedPositionsTotal(7);
        session.setClosedPositionsTotal(4);
        session.setStopReason("TARGET_REACHED");
        session.setTraceId("trace-stop-requested");
        session.setStartedBy("operator-1");
        session.setStartedAt(now.minusSeconds(60));
        session.setCreatedAt(now.minusSeconds(90));
        session.setUpdatedAt(now);
        session = sessionRepository.save(session);

        BudgetTargetSessionEvent stopRequested = new BudgetTargetSessionEvent();
        stopRequested.setSession(session);
        stopRequested.setEventType("STOP_REQUESTED");
        stopRequested.setEventStatus("STOPPING");
        stopRequested.setReasonCode("TARGET_REACHED");
        stopRequested.setEventCategory(BudgetTargetAuditEventCategory.SESSION);
        stopRequested.setSeverity(BudgetTargetAuditSeverity.INFO);
        stopRequested.setActor("system");
        stopRequested.setBeforeJson(objectMapper.writeValueAsString(Map.of("session", Map.of("status", "TARGET_REACHED"))));
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("requestedBy", "system");
        payload.put("pendingScanRunId", null);
        stopRequested.setAfterJson(objectMapper.writeValueAsString(Map.of(
                "session", Map.of("status", "STOPPING"),
                "payload", payload)));
        stopRequested.setNotes("Target-reached session is transitioning into STOPPING.");
        stopRequested.setTraceId(session.getTraceId());
        stopRequested.setEventTs(now);
        stopRequested = sessionEventRepository.save(stopRequested);

        BudgetTargetEventTimelineItemDTO item = service.toTimelineItem(stopRequested);

        assertThat(item.getEventType()).isEqualTo("STOP_REQUESTED");
        assertThat(item.getSummaryPayload())
                .containsEntry("requestedBy", "system")
                .containsKey("pendingScanRunId");
        assertThat(item.getSummaryPayload().get("pendingScanRunId")).isNull();
    }

    private Recommendation recommendation(String symbol, ScanRun scanRun) {
        Recommendation recommendation = new Recommendation();
        recommendation.setScanRun(scanRun);
        recommendation.setSymbol(symbol);
        recommendation.setSide("BUY");
        recommendation.setCreatedAt(Instant.now());
        recommendation.setStatus("ACTIVE");
        return recommendationRepository.save(recommendation);
    }

    private LiveTradeExecution execution(BudgetTargetSession session,
            Recommendation recommendation,
            String symbol,
            int positionSlot,
            BigDecimal budgetSlice,
            BigDecimal reservedMargin,
            BigDecimal gross,
            BigDecimal fees,
            BigDecimal net,
            String closeReason,
            Instant submittedAt,
            Instant completedAt) throws Exception {
        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setRecommendation(recommendation);
        execution.setSession(session);
        execution.setTriggerMode(LiveTradeTriggerMode.AUTO_SESSION);
        execution.setSymbol(symbol);
        execution.setSide("BUY");
        execution.setOperatorId("system");
        execution.setTraceId("trace-" + symbol);
        execution.setClientRequestId(UUID.randomUUID());
        execution.setDryRun(false);
        execution.setExecutionState(LiveTradeExecutionState.CLOSED);
        execution.setReservedMarginUsdt(reservedMargin);
        execution.setRequestedBudgetSliceUsdt(budgetSlice);
        execution.setRequestedQty(new BigDecimal("0.010"));
        execution.setActualFilledQty(new BigDecimal("0.010"));
        execution.setRealizedGrossPnlUsdt(gross);
        execution.setRealizedFeesUsdt(fees);
        execution.setRealizedNetPnlUsdt(net);
        execution.setCloseReason(closeReason);
        execution.setEntryClientOrderId(symbol.toLowerCase() + "-entry");
        execution.setEntryOrderId(positionSlot == 1 ? 1001L : 1002L);
        execution.setPositionSlot(positionSlot);
        execution.setPayloadSnapshotJson(objectMapper.writeValueAsString(Map.of("request", Map.of("operatorNote", "fallback open reason"))));
        execution.setExchangeResponseJson(objectMapper.writeValueAsString(Map.of("entry", Map.of("status", "FILLED"))));
        execution.setCriticalIssueJson(positionSlot == 2
                ? objectMapper.writeValueAsString(Map.of(
                        "code", "UPSTREAM_TIMEOUT",
                        "message", "Emergency close timed out.",
                        "raisedAt", Instant.parse("2026-03-09T00:16:00Z").toString()))
                : null);
        execution.setSubmittedAt(submittedAt);
        execution.setCompletedAt(completedAt);
        execution.setCreatedAt(submittedAt.minusSeconds(5));
        execution.setUpdatedAt(completedAt);
        return executionRepository.save(execution);
    }

    private BudgetTargetSessionEvent sessionEvent(BudgetTargetSession session,
            LiveTradeExecution execution,
            String eventType,
            String eventStatus,
            String notes,
            String reasonCode,
            Instant eventTs,
            String actor,
            BudgetTargetAuditSeverity severity,
            Map<String, Object> before,
            Map<String, Object> after) throws Exception {
        BudgetTargetSessionEvent event = new BudgetTargetSessionEvent();
        event.setSession(session);
        event.setExecution(execution);
        event.setEventType(eventType);
        event.setEventStatus(eventStatus);
        event.setReasonCode(reasonCode);
        event.setEventCategory(BudgetTargetAuditEventCategory.SESSION);
        event.setSeverity(severity);
        event.setActor(actor);
        event.setBeforeJson(objectMapper.writeValueAsString(before));
        event.setAfterJson(objectMapper.writeValueAsString(after));
        event.setNotes(notes);
        event.setTraceId(session.getTraceId());
        event.setEventTs(eventTs);
        return event;
    }

    private SessionSymbolDecisionAudit decisionAudit(BudgetTargetSession session,
            ScanRun scanRun,
            Recommendation recommendation,
            LiveTradeExecution execution,
            String symbol,
            String eventType,
            Instant eventTs,
            BudgetTargetAuditSeverity severity,
            String notes,
            Map<String, Object> before,
            Map<String, Object> after) throws Exception {
        SessionSymbolDecisionAudit audit = new SessionSymbolDecisionAudit();
        audit.setSession(session);
        audit.setScanRun(scanRun);
        audit.setRecommendation(recommendation);
        audit.setExecution(execution);
        audit.setSymbol(symbol);
        audit.setEventType(eventType);
        audit.setEventTs(eventTs);
        audit.setEventCategory(BudgetTargetAuditEventCategory.TRADE);
        audit.setSeverity(severity);
        audit.setActor("system");
        audit.setBeforeJson(objectMapper.writeValueAsString(before));
        audit.setAfterJson(objectMapper.writeValueAsString(after));
        audit.setNotes(notes);
        audit.setTraceId(session.getTraceId());
        return audit;
    }

    private LiveTradeExecutionEvent executionEvent(LiveTradeExecution execution,
            String eventType,
            String eventStatus,
            String errorCode,
            Instant eventTs,
            BudgetTargetAuditEventCategory category,
            BudgetTargetAuditSeverity severity,
            String notes,
            Map<String, Object> before,
            Map<String, Object> after) throws Exception {
        LiveTradeExecutionEvent event = new LiveTradeExecutionEvent();
        event.setExecution(execution);
        event.setEventType(eventType);
        event.setEventStatus(eventStatus);
        event.setErrorCode(errorCode);
        event.setEventCategory(category);
        event.setSeverity(severity);
        event.setActor("system");
        event.setBeforeJson(objectMapper.writeValueAsString(before));
        event.setAfterJson(objectMapper.writeValueAsString(after));
        event.setNotes(notes);
        event.setTraceId(execution.getTraceId());
        event.setEventTs(eventTs);
        return event;
    }

    private LiveTradeOrder order(LiveTradeExecution execution, BudgetTargetSession session, String role, String clientOrderId, long exchangeOrderId) throws Exception {
        LiveTradeOrder order = new LiveTradeOrder();
        order.setExecution(execution);
        order.setSession(session);
        order.setSymbol(execution.getSymbol());
        order.setOrderRole(role);
        order.setClientOrderId(clientOrderId);
        order.setExchangeOrderId(exchangeOrderId);
        order.setRequestedQty(new BigDecimal("0.010"));
        order.setExecutedQty(new BigDecimal("0.010"));
        order.setAvgFillPrice(new BigDecimal("100000"));
        order.setOrderStatus("FILLED");
        order.setRequestJson(objectMapper.writeValueAsString(Map.of("clientOrderId", clientOrderId)));
        order.setResponseJson(objectMapper.writeValueAsString(Map.of("orderId", exchangeOrderId, "status", "FILLED")));
        order.setSnapshotJson(objectMapper.writeValueAsString(Map.of("orderRole", role)));
        order.setTraceId(execution.getTraceId());
        order.setCreatedAt(execution.getSubmittedAt());
        order.setUpdatedAt(execution.getCompletedAt());
        return order;
    }

    private LiveTradeClosure closure(LiveTradeExecution execution, BudgetTargetSession session, String closeReason, Instant closedAt) throws Exception {
        LiveTradeClosure closure = new LiveTradeClosure();
        closure.setExecution(execution);
        closure.setSession(session);
        closure.setCloseReason(closeReason);
        closure.setClosedQty(new BigDecimal("0.010"));
        closure.setClosedPrice(new BigDecimal("100500"));
        closure.setClosingClientOrderId(execution.getSymbol().toLowerCase() + "-close");
        closure.setClosingOrderId(execution.getEntryOrderId() + 5000);
        closure.setFinalPositionSnapshotJson(objectMapper.writeValueAsString(Map.of("positionAmt", "0")));
        closure.setCloseResponseJson(objectMapper.writeValueAsString(Map.of("status", "FILLED")));
        closure.setTraceId(execution.getTraceId());
        closure.setClosedAt(closedAt);
        return closure;
    }

    private ExchangeSyncSnapshot syncSnapshot(BudgetTargetSession session,
            LiveTradeExecution execution,
            String syncStatus,
            boolean divergenceDetected,
            boolean requiresIntervention,
            boolean openPosition,
            int activeOpenOrderCount,
            Instant syncCompletedAt,
            Instant lastSuccessfulSyncAt,
            String errorCode,
            String errorMessage) throws Exception {
        ExchangeSyncSnapshot snapshot = new ExchangeSyncSnapshot();
        snapshot.setSession(session);
        snapshot.setExecution(execution);
        snapshot.setSymbol(execution.getSymbol());
        snapshot.setSyncType("SCHEDULED");
        snapshot.setSyncStatus(syncStatus);
        snapshot.setTraceId("trace-sync-" + execution.getId());
        snapshot.setErrorCode(errorCode);
        snapshot.setErrorMessage(errorMessage);
        snapshot.setDivergenceDetected(divergenceDetected);
        snapshot.setRequiresIntervention(requiresIntervention);
        snapshot.setOpenPosition(openPosition);
        snapshot.setActiveOpenOrderCount(activeOpenOrderCount);
        snapshot.setActiveProtectionOrderCount(Math.min(activeOpenOrderCount, 2));
        snapshot.setStopLossActive(activeOpenOrderCount > 0);
        snapshot.setTakeProfitActive(activeOpenOrderCount > 1);
        snapshot.setEmergencyCloseWorking(false);
        snapshot.setEmergencyCloseFilled(false);
        snapshot.setProtectionTriggered(false);
        snapshot.setEntryOrderStatus(openPosition ? "FILLED" : "CLOSED");
        snapshot.setStopLossStatus(activeOpenOrderCount > 0 ? "NEW" : null);
        snapshot.setTakeProfitStatus(activeOpenOrderCount > 1 ? "NEW" : null);
        snapshot.setPositionQuantity(openPosition ? new BigDecimal("0.01000000") : BigDecimal.ZERO);
        snapshot.setActualFilledQty(new BigDecimal("0.01000000"));
        snapshot.setAvgFillPrice(new BigDecimal("100000"));
        snapshot.setEntryPrice(new BigDecimal("100000"));
        snapshot.setMarkPrice(new BigDecimal("100050"));
        snapshot.setRealizedGrossPnlUsdt(execution.getRealizedGrossPnlUsdt());
        snapshot.setRealizedFeesUsdt(execution.getRealizedFeesUsdt());
        snapshot.setRealizedNetPnlUsdt(execution.getRealizedNetPnlUsdt());
        snapshot.setUnrealizedPnlUsdt(openPosition ? new BigDecimal("0.50") : BigDecimal.ZERO);
        snapshot.setLastSuccessfulSyncAt(lastSuccessfulSyncAt);
        snapshot.setSyncCompletedAt(syncCompletedAt);
        snapshot.setSnapshotJson(objectMapper.writeValueAsString(Map.of(
                "syncStatus", syncStatus,
                "openPosition", openPosition,
                "activeOpenOrderCount", activeOpenOrderCount)));
        snapshot.setCreatedAt(syncCompletedAt);
        return snapshot;
    }

    private LiveTradePnlLedger ledger(BudgetTargetSession session,
            LiveTradeExecution execution,
            String eventType,
            BigDecimal amount,
            String sourceRef,
            Instant eventTs) throws Exception {
        LiveTradePnlLedger ledger = new LiveTradePnlLedger();
        ledger.setSession(session);
        ledger.setExecution(execution);
        ledger.setEventType(eventType);
        ledger.setAmountUsdt(amount);
        ledger.setEventTs(eventTs);
        ledger.setSourceType(eventType);
        ledger.setSourceRef(sourceRef);
        ledger.setBeforeJson(objectMapper.writeValueAsString(Map.of("amountUsdt", "0")));
        ledger.setAfterJson(objectMapper.writeValueAsString(Map.of("amountUsdt", amount.toPlainString())));
        ledger.setNotes(eventType);
        ledger.setTraceId(session.getTraceId());
        return ledger;
    }
}
