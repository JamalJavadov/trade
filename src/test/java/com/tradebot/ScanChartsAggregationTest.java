package com.tradebot;

import com.tradebot.dto.ScanChartsDTO;
import com.tradebot.entity.SymbolEvaluation;
import com.tradebot.repository.BestCandidateEventRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.ScanPhaseEventRepository;
import com.tradebot.repository.ScanRunRepository;
import com.tradebot.repository.SymbolEvaluationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.service.ExplainabilityService;
import com.tradebot.service.ScanQueryService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScanChartsAggregationTest {

    private final ScanQueryService service = new ScanQueryService(
            mock(ScanRunRepository.class),
            mock(ScanPhaseEventRepository.class),
            mock(SymbolEvaluationRepository.class),
            mock(RecommendationRepository.class),
            mock(BestCandidateEventRepository.class),
            mock(ExplainabilityService.class),
            new ObjectMapper());

    @Test
    void histogramBinsAreCorrectlyDistributed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<SymbolEvaluation> rows = new ArrayList<>();

        for (int i = 1; i <= 10; i++) {
            SymbolEvaluation ev = new SymbolEvaluation();
            ev.setId(UUID.randomUUID());
            ev.setScanRunId(UUID.randomUUID());
            ev.setSymbol("SYM" + i);
            ev.setRankInUniverse(i);
            ev.setQuoteVolumeUsdt(BigDecimal.valueOf(1_000_000L));
            ev.setBias("UPTREND");
            ev.setDecision("VALID");
            ev.setSide("LONG");
            ev.setCreatedAt(Instant.now());

            double rr = i * 0.5;
            double conf = i * 0.08;
            Map<String, Object> metrics = Map.of(
                    "rr_tp1", rr,
                    "confidence_score", Math.min(conf, 1.0),
                    "final_score", rr,
                    "entry", "100",
                    "sl", "95",
                    "tp1", "110");
            ev.setMetricsJson(mapper.writeValueAsString(metrics));
            rows.add(ev);
        }

        for (int i = 11; i <= 20; i++) {
            SymbolEvaluation ev = new SymbolEvaluation();
            ev.setId(UUID.randomUUID());
            ev.setScanRunId(UUID.randomUUID());
            ev.setSymbol("SYM" + i);
            ev.setRankInUniverse(i);
            ev.setQuoteVolumeUsdt(BigDecimal.valueOf(500_000L));
            ev.setBias("DOWNTREND");
            ev.setDecision("NO_TRADE");
            ev.setSide("NONE");
            ev.setSkipReasonCode(i % 2 == 0 ? "NO_BIAS" : "RR_TOO_LOW");
            ev.setCreatedAt(Instant.now());
            rows.add(ev);
        }

        ScanChartsDTO charts = service.buildCharts(rows);

        assertNotNull(charts.getRrHist());
        assertEquals(10, charts.getRrHist().size(), "Should have 10 histogram bins");
        assertNotNull(charts.getRrHist().get(0).getBucketStart());
        assertNotNull(charts.getRrHist().get(0).getBucketEnd());

        int totalInBins = charts.getRrHist().stream().mapToInt(ScanChartsDTO.HistBin::getCount).sum();
        assertEquals(10, totalInBins, "All VALID rr values should appear in histogram");

        assertNotNull(charts.getSkipReasonBreakdown());
        int skipTotal = charts.getSkipReasonBreakdown().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(10, skipTotal, "Skip breakdown should sum to noTradeCount");

        assertTrue(charts.getScatterPoints().size() <= 300, "Scatter points must not exceed 300");
        assertEquals(10, charts.getScatterPoints().size(), "One scatter point per VALID symbol");

        assertNotNull(charts.getBiasBreakdown());
        assertEquals(10, charts.getBiasBreakdown().get("UPTREND"));
        assertEquals(10, charts.getBiasBreakdown().get("DOWNTREND"));
    }

    @Test
    void emptyRowsProduceEmptyHistograms() {
        ScanChartsDTO charts = service.buildCharts(List.of());
        assertTrue(charts.getRrHist().isEmpty());
        assertTrue(charts.getConfHist().isEmpty());
        assertTrue(charts.getScatterPoints().isEmpty());
        assertTrue(charts.getSkipReasonBreakdown().isEmpty());
    }
}
