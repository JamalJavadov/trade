package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.config.AppProperties;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.entity.ScanRun;
import com.tradebot.repository.BestCandidateEventRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.ScanPhaseEventRepository;
import com.tradebot.repository.ScanRunRepository;
import com.tradebot.repository.SymbolEvaluationRepository;
import com.tradebot.repository.SymbolUniverseSnapshotRepository;
import com.tradebot.service.BiasDetector;
import com.tradebot.service.FibonacciCalculator;
import com.tradebot.service.ImpulseLegDetector;
import com.tradebot.service.RiskAndSizingCalculator;
import com.tradebot.service.ScanOrchestrator;
import com.tradebot.service.StrategyConfigProvider;
import com.tradebot.service.SweepReclaimDetector;
import com.tradebot.sse.ScanEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScanOrchestratorRunOnceIdempotencyTest {

    @Test
    void runOnceReturnsExistingRunningRunInsteadOfThrowingConflict() {
        ScanRunRepository scanRunRepository = mock(ScanRunRepository.class);
        TaskExecutor taskExecutor = mock(TaskExecutor.class);
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);

        ControlCenterConfig config = new ControlCenterConfig();
        config.getScan().setIntervalMinutes(20);
        when(settingsProvider.getConfigSnapshot()).thenReturn(config);

        ScanRun running = new ScanRun();
        running.setId(UUID.randomUUID());
        running.setStatus("STARTED");
        running.setStartedAt(Instant.now());
        running.setIntervalMinutes(20);
        running.setTopN(300);
        when(scanRunRepository.findFirstByStatusOrderByStartedAtDesc("STARTED")).thenReturn(Optional.of(running));

        ScanOrchestrator orchestrator = new ScanOrchestrator(
                mock(BinanceClient.class),
                mock(BiasDetector.class),
                mock(ImpulseLegDetector.class),
                mock(FibonacciCalculator.class),
                mock(SweepReclaimDetector.class),
                mock(RiskAndSizingCalculator.class),
                scanRunRepository,
                mock(SymbolUniverseSnapshotRepository.class),
                mock(RecommendationRepository.class),
                mock(ScanPhaseEventRepository.class),
                mock(SymbolEvaluationRepository.class),
                mock(BestCandidateEventRepository.class),
                mock(StrategyConfigProvider.class),
                settingsProvider,
                new AppProperties(),
                new ObjectMapper(),
                mock(ScanEventPublisher.class),
                taskExecutor);

        ScanOrchestrator.ScanStartResult result = orchestrator.runOnce("trace-1");

        assertEquals(running.getId(), result.scanRunId());
        assertEquals("ALREADY_RUNNING", result.status());
        assertFalse(result.startedNew());
        verifyNoInteractions(taskExecutor);
    }
}
