package com.tradebot;

import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionEvent;
import com.tradebot.entity.BudgetTargetSessionStatus;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.LiveTradePnlLedger;
import com.tradebot.entity.LiveTradeTriggerMode;
import com.tradebot.entity.Recommendation;
import com.tradebot.entity.ScanRun;
import com.tradebot.entity.SessionSymbolDecisionAudit;
import com.tradebot.repository.BudgetTargetSessionEventRepository;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.LiveTradePnlLedgerRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.ScanRunRepository;
import com.tradebot.repository.SessionSymbolDecisionAuditRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BudgetTargetPersistenceRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tradebot_repo")
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
    private LiveTradePnlLedgerRepository pnlLedgerRepository;

    @Autowired
    private SessionSymbolDecisionAuditRepository auditRepository;

    @Autowired
    private ScanRunRepository scanRunRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    @Test
    void repositoriesSupportSessionStatusTimelineAndSymbolLookups() {
        ScanRun scanRun = new ScanRun();
        scanRun.setStartedAt(Instant.now());
        scanRun.setRequestedAt(Instant.now());
        scanRun.setIntervalMinutes(20);
        scanRun.setTopN(10);
        scanRun.setStatus("FINISHED");
        scanRun.setTriggerType("AUTO_SESSION");
        scanRun = scanRunRepository.save(scanRun);

        Recommendation recommendation = new Recommendation();
        recommendation.setScanRun(scanRun);
        recommendation.setSymbol("BTCUSDT");
        recommendation.setSide("BUY");
        recommendation.setCreatedAt(Instant.now());
        recommendation.setStatus("ACTIVE");
        recommendation = recommendationRepository.save(recommendation);

        BudgetTargetSession session = new BudgetTargetSession();
        session.setStatus(BudgetTargetSessionStatus.RUNNING);
        session.setBudgetAmountUsdt(new BigDecimal("50"));
        session.setTargetProfitUsdt(new BigDecimal("10"));
        session.setRealizedNetPnlUsdt(BigDecimal.ZERO);
        session.setUnrealizedNetPnlUsdt(BigDecimal.ZERO);
        session.setMaxConcurrentPositions(3);
        session.setActivePositionsCount(1);
        session.setOpenedPositionsTotal(1);
        session.setClosedPositionsTotal(0);
        session.setExecutionFailureCount(2);
        session.setTraceId("trace-session");
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setConfigSnapshotJson("{}");
        session = sessionRepository.save(session);

        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setRecommendation(recommendation);
        execution.setSession(session);
        execution.setTriggerMode(LiveTradeTriggerMode.AUTO_SESSION);
        execution.setSymbol("BTCUSDT");
        execution.setSide("BUY");
        execution.setTraceId("trace-execution");
        execution.setClientRequestId(UUID.randomUUID());
        execution.setDryRun(false);
        execution.setExecutionStatus(LiveTradeExecutionState.ACTIVE);
        execution.setPositionSlot(1);
        execution.setRequestedQty(new BigDecimal("0.010"));
        execution.setActualFilledQty(new BigDecimal("0.010"));
        execution.setCreatedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        execution = executionRepository.save(execution);

        BudgetTargetSessionEvent firstEvent = new BudgetTargetSessionEvent();
        firstEvent.setSession(session);
        firstEvent.setExecution(execution);
        firstEvent.setEventType("SCAN_REQUESTED");
        firstEvent.setEventStatus("RUNNING");
        firstEvent.setAfterJson("{\"step\":1}");
        firstEvent.setTraceId("trace-session");
        firstEvent.setEventTs(Instant.now().minusSeconds(5));
        sessionEventRepository.save(firstEvent);

        BudgetTargetSessionEvent secondEvent = new BudgetTargetSessionEvent();
        secondEvent.setSession(session);
        secondEvent.setExecution(execution);
        secondEvent.setEventType("RECOMMENDATION_EXECUTED");
        secondEvent.setEventStatus("RUNNING");
        secondEvent.setAfterJson("{\"step\":2}");
        secondEvent.setTraceId("trace-session");
        secondEvent.setEventTs(Instant.now());
        sessionEventRepository.save(secondEvent);

        LiveTradePnlLedger pnlLedger = new LiveTradePnlLedger();
        pnlLedger.setSession(session);
        pnlLedger.setExecution(execution);
        pnlLedger.setEventType("REALIZED_GROSS_PNL");
        pnlLedger.setAmountUsdt(new BigDecimal("1.25"));
        pnlLedger.setEventTs(Instant.now());
        pnlLedger.setSourceType("EXECUTION_GROSS_PNL");
        pnlLedger.setSourceRef(execution.getId() + ":gross");
        pnlLedger.setTraceId("trace-ledger");
        pnlLedgerRepository.save(pnlLedger);

        SessionSymbolDecisionAudit audit = new SessionSymbolDecisionAudit();
        audit.setSession(session);
        audit.setScanRun(scanRun);
        audit.setRecommendation(recommendation);
        audit.setExecution(execution);
        audit.setSymbol("BTCUSDT");
        audit.setEventType("EXECUTION_SUBMITTED");
        audit.setEventTs(Instant.now());
        audit.setTraceId("trace-session");
        auditRepository.save(audit);

        assertThat(sessionRepository.findFirstByStatusInOrderByCreatedAtDesc(List.of(BudgetTargetSessionStatus.RUNNING)))
                .isPresent()
                .get()
                .extracting(BudgetTargetSession::getExecutionFailureCount)
                .isEqualTo(2);
        assertThat(sessionEventRepository.findTop50BySession_IdOrderByEventTsDesc(session.getId()))
                .extracting(BudgetTargetSessionEvent::getEventType)
                .containsExactly("RECOMMENDATION_EXECUTED", "SCAN_REQUESTED");
        assertThat(executionRepository.findBySession_IdAndExecutionStatusInOrderByCreatedAtAsc(
                session.getId(),
                List.of(LiveTradeExecutionState.ACTIVE)))
                .hasSize(1);
        assertThat(pnlLedgerRepository.sumNetPnlBySessionId(session.getId()))
                .isEqualByComparingTo("1.25");
        assertThat(auditRepository.findBySession_IdAndSymbolOrderByEventTsDesc(session.getId(), "BTCUSDT"))
                .extracting(SessionSymbolDecisionAudit::getEventType)
                .containsExactly("EXECUTION_SUBMITTED");
    }
}
