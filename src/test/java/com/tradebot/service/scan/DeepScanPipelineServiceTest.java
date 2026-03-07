package com.tradebot.service.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.config.AppProperties;
import com.tradebot.dto.StrategyTuningConfig;
import com.tradebot.repository.ScanCandidateEventRepository;
import com.tradebot.service.RiskAndSizingCalculator;
import com.tradebot.sse.ScanEventPublisher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepScanPipelineServiceTest {

    @Test
    void aiUnavailableDoesNotBlockDeterministicEligibleCandidate() {
        DataIntegrityValidationService integrityService = mock(DataIntegrityValidationService.class);
        DeterministicStrategyEvaluator deterministicEvaluator = mock(DeterministicStrategyEvaluator.class);
        DeepStructuralValidationService validationService = mock(DeepStructuralValidationService.class);
        SecondPassConfirmationService confirmationService = mock(SecondPassConfirmationService.class);
        ScanReviewAiService aiReviewService = mock(ScanReviewAiService.class);

        DeepScanPipelineService service = new DeepScanPipelineService(
                integrityService,
                deterministicEvaluator,
                validationService,
                confirmationService,
                aiReviewService);

        FrozenSymbolSnapshot snapshot = new FrozenSymbolSnapshot(
                UUID.randomUUID(),
                "trace-deep-scan",
                "BTCUSDT",
                1,
                new BigDecimal("1000000"),
                Instant.parse("2026-03-06T12:00:00Z"),
                "15m",
                "1h",
                Map.of(
                        "tickSize", "0.1",
                        "stepSize", "0.001",
                        "minQty", "0.001",
                        "status", "TRADING",
                        "contractType", "PERPETUAL",
                        "quoteAsset", "USDT"),
                List.of(),
                List.of());

        Instant now = Instant.now();
        StageAudit passedIntegrity = new StageAudit(DeepScanStage.DATA_INTEGRITY, "PASSED", List.of(), Map.of(), now, now);
        StageAudit passedValidation = new StageAudit(DeepScanStage.STRUCTURAL_VALIDATION, "PASSED", List.of(), Map.of(), now, now);
        StageAudit passedConfirmation = new StageAudit(DeepScanStage.SECOND_PASS_CONFIRMATION, "PASSED", List.of(), Map.of(), now, now);

        RiskAndSizingCalculator.ExecutionPlan plan = new RiskAndSizingCalculator.ExecutionPlan(
                new BigDecimal("100.0"),
                new BigDecimal("95.0"),
                new BigDecimal("110.0"),
                new BigDecimal("115.0"),
                new BigDecimal("120.0"),
                new BigDecimal("1.000"),
                new BigDecimal("2.00"));
        DeterministicStrategyResult deterministicResult = new DeterministicStrategyResult(
                "VALID",
                "LONG",
                "UPTREND",
                null,
                null,
                true,
                null,
                null,
                null,
                plan,
                Map.of(
                        "rr_tp1", new BigDecimal("2.00"),
                        "final_score", new BigDecimal("2.00"),
                        "confidence_score", new BigDecimal("2.00"),
                        "entry", new BigDecimal("100.0"),
                        "sl", new BigDecimal("95.0"),
                        "tp1", new BigDecimal("110.0"),
                        "tp2", new BigDecimal("115.0"),
                        "tp3", new BigDecimal("120.0"),
                        "quantity", new BigDecimal("1.000")),
                Map.of(),
                Map.of());

        when(integrityService.evaluate(snapshot)).thenReturn(passedIntegrity);
        when(deterministicEvaluator.evaluate(any(), any(), any())).thenReturn(deterministicResult);
        when(validationService.evaluate(snapshot, deterministicResult)).thenReturn(passedValidation);
        when(confirmationService.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(new SecondPassConfirmationService.ConfirmationResult(passedConfirmation, ConflictReport.none()));
        when(aiReviewService.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(AiReviewAggregate.unavailable("AI unavailable", 8, List.of()));

        ScanCandidateEventRepository eventRepository = mock(ScanCandidateEventRepository.class);
        ScanEventPublisher eventPublisher = mock(ScanEventPublisher.class);
        ScanCandidateEventService eventService = new ScanCandidateEventService(eventRepository, eventPublisher, new ObjectMapper());

        DeepScanCandidateResult result = service.evaluate(
                snapshot,
                new AppProperties(),
                new StrategyTuningConfig(),
                eventService.recorder(snapshot.scanRunId(), snapshot.symbol()));

        assertTrue(result.finalGate().eligible());
        assertEquals("UNAVAILABLE", result.aiReview().status());
        assertTrue(result.finalGate().finalIntegrityScore() >= 80);
        verify(eventRepository, atLeast(1)).save(any());
        verify(eventPublisher, atLeast(1)).publish(any(), any(), any());
    }
}
