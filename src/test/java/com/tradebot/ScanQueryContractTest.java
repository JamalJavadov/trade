package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.dto.SymbolEvaluationDetailDTO;
import com.tradebot.entity.SymbolEvaluation;
import com.tradebot.repository.BestCandidateEventRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.ScanPhaseEventRepository;
import com.tradebot.repository.ScanRunRepository;
import com.tradebot.repository.SymbolEvaluationRepository;
import com.tradebot.service.ExplainabilityService;
import com.tradebot.service.ScanQueryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScanQueryContractTest {

    @Test
    void evaluationDetailExposesLiveScanFieldNames() throws Exception {
        ScanRunRepository scanRunRepository = mock(ScanRunRepository.class);
        ScanPhaseEventRepository scanPhaseEventRepository = mock(ScanPhaseEventRepository.class);
        SymbolEvaluationRepository evaluationRepository = mock(SymbolEvaluationRepository.class);
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        BestCandidateEventRepository bestCandidateEventRepository = mock(BestCandidateEventRepository.class);
        ExplainabilityService explainabilityService = mock(ExplainabilityService.class);

        ScanQueryService service = new ScanQueryService(
                scanRunRepository,
                scanPhaseEventRepository,
                evaluationRepository,
                recommendationRepository,
                bestCandidateEventRepository,
                explainabilityService,
                new ObjectMapper());

        UUID scanRunId = UUID.randomUUID();
        SymbolEvaluation row = new SymbolEvaluation();
        row.setScanRunId(scanRunId);
        row.setSymbol("TESTUSDT");
        row.setDecision("VALID");
        row.setSide("LONG");
        row.setBias("UPTREND");
        row.setRankInUniverse(7);
        row.setQuoteVolumeUsdt(new BigDecimal("12345.67"));
        row.setCreatedAt(Instant.now());
        row.setMetricsJson(new ObjectMapper().writeValueAsString(Map.of(
                "final_score", 3.2,
                "confidence_score", 2.8,
                "rr_tp1", 3.1,
                "entry", "100.0",
                "sl", "98.0",
                "tp1", "106.0")));

        when(evaluationRepository.findByScanRunIdAndSymbol(scanRunId, "TESTUSDT"))
                .thenReturn(java.util.Optional.of(row));

        SymbolEvaluationDetailDTO dto = service.getEvaluationDetail(scanRunId, "TESTUSDT");

        assertEquals(7, dto.getRankInUniverse());
        assertEquals(2.8, dto.getConfidence());
        assertEquals(2.8, dto.getConfidenceScore());
    }
}
