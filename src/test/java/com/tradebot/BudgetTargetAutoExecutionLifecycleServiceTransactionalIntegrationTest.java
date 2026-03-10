package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.BudgetTargetAutoExecutionStateRequestDTO;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionCompletionReason;
import com.tradebot.entity.BudgetTargetSessionEvent;
import com.tradebot.entity.BudgetTargetSessionStatus;
import com.tradebot.repository.BudgetTargetSessionEventRepository;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.service.BudgetTargetAutoExecutionLifecycleService;
import com.tradebot.service.BudgetTargetSessionStreamPublisher;
import com.tradebot.service.LiveTradingPreflightService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        BudgetTargetAutoExecutionLifecycleService.class,
        BudgetTargetAutoExecutionLifecycleServiceTransactionalIntegrationTest.TestConfig.class
})
class BudgetTargetAutoExecutionLifecycleServiceTransactionalIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tradebot_lifecycle_tx")
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
    private BudgetTargetAutoExecutionLifecycleService service;

    @Autowired
    private BudgetTargetSessionRepository sessionRepository;

    @Autowired
    private BudgetTargetSessionEventRepository eventRepository;

    @MockBean
    private ControlCenterSettingsProvider settingsProvider;

    @MockBean
    private LiveTradingPreflightService liveTradingPreflightService;

    @MockBean
    private BudgetTargetSessionStreamPublisher streamPublisher;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void applyCommandTurnOffStartsOwnTransactionForLockedSessionLookup() {
        ControlCenterConfig.BudgetTargetAutoExecution runtime = new ControlCenterConfig.BudgetTargetAutoExecution();
        runtime.setEnabled(true);
        runtime.setArmed(true);
        runtime.setRequireOperatorConfirmationForStop(false);
        when(settingsProvider.getBudgetTargetAutoExecutionSettings()).thenReturn(runtime);
        when(settingsProvider.patchOperational(any(), anyString(), anyString())).thenReturn(new ControlCenterConfig());

        Instant now = Instant.parse("2026-03-09T18:30:00Z");
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
        session.setExecutionFailureCount(0);
        session.setStartedBy("tester");
        session.setTraceId("trace-turn-off-tx");
        session.setStartedAt(now.minusSeconds(300));
        session.setCreatedAt(now.minusSeconds(300));
        session.setUpdatedAt(now.minusSeconds(60));
        session.setConfigSnapshotJson("{}");
        session = sessionRepository.save(session);

        BudgetTargetAutoExecutionStateRequestDTO request = new BudgetTargetAutoExecutionStateRequestDTO();
        request.setCommand(BudgetTargetAutoExecutionStateRequestDTO.Command.TURN_OFF);

        service.applyCommand(request, "tester");

        BudgetTargetSession updated = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(BudgetTargetSessionStatus.STOPPING);
        assertThat(updated.getCompletionReason()).isEqualTo(BudgetTargetSessionCompletionReason.OPERATOR_STOPPED);
        assertThat(updated.getStopReason()).isEqualTo("OPERATOR_STOPPED");
        assertThat(updated.isStopRequested()).isTrue();
        assertThat(updated.getStoppedBy()).isEqualTo("tester");

        assertThat(eventRepository.findBySession_IdOrderByEventTsAsc(session.getId()))
                .extracting(BudgetTargetSessionEvent::getEventType)
                .contains("STOP_REQUESTED");
        assertThat(eventRepository.findBySession_IdOrderByEventTsAsc(session.getId()))
                .extracting(BudgetTargetSessionEvent::getReasonCode)
                .contains("OPERATOR_STOPPED");
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
