package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.config.AppProperties;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

class ScanOrchestratorIntervalTest {

    @Test
    void scheduledDedupKeyChangesWhenWindowChanges() {
        ScanOrchestrator orchestrator = newOrchestrator();
        Instant firstWindow = Instant.parse("2026-03-05T12:01:00Z");
        Instant secondWindow = Instant.parse("2026-03-05T12:06:00Z");

        String keyWindow1 = ReflectionTestUtils.invokeMethod(orchestrator, "scheduledWindowDedupKey", 5, firstWindow);
        String keyWindow2 = ReflectionTestUtils.invokeMethod(orchestrator, "scheduledWindowDedupKey", 5, secondWindow);

        assertNotEquals(keyWindow1, keyWindow2);
    }

    private ScanOrchestrator newOrchestrator() {
        return new ScanOrchestrator(
                mock(BinanceClient.class),
                mock(BiasDetector.class),
                mock(ImpulseLegDetector.class),
                mock(FibonacciCalculator.class),
                mock(SweepReclaimDetector.class),
                mock(RiskAndSizingCalculator.class),
                mock(ScanRunRepository.class),
                mock(SymbolUniverseSnapshotRepository.class),
                mock(RecommendationRepository.class),
                mock(ScanPhaseEventRepository.class),
                mock(SymbolEvaluationRepository.class),
                mock(BestCandidateEventRepository.class),
                mock(StrategyConfigProvider.class),
                mock(ControlCenterSettingsProvider.class),
                new AppProperties(),
                new ObjectMapper(),
                mock(ScanEventPublisher.class),
                mock(TaskExecutor.class));
    }
}
