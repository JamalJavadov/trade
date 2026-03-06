package com.tradebot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.dto.ExplanationDTO;
import com.tradebot.dto.PhaseDTO;
import com.tradebot.dto.BestCandidateEventDTO;
import com.tradebot.dto.ScanChartsDTO;
import com.tradebot.dto.ScanPhaseDTO;
import com.tradebot.dto.ScanReplayDTO;
import com.tradebot.dto.ScanSummaryDTO;
import com.tradebot.dto.SymbolEvaluationDetailDTO;
import com.tradebot.dto.SymbolEvaluationRowDTO;
import com.tradebot.entity.BestCandidateEvent;
import com.tradebot.entity.ScanPhaseEvent;
import com.tradebot.entity.ScanRun;
import com.tradebot.entity.SymbolEvaluation;
import com.tradebot.repository.BestCandidateEventRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.ScanPhaseEventRepository;
import com.tradebot.repository.ScanRunRepository;
import com.tradebot.repository.SymbolEvaluationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScanQueryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ScanRunRepository scanRunRepository;
    private final ScanPhaseEventRepository phaseEventRepository;
    private final SymbolEvaluationRepository evaluationRepository;
    private final RecommendationRepository recommendationRepository;
    private final BestCandidateEventRepository bestCandidateEventRepository;
    private final ExplainabilityService explainabilityService;
    private final ObjectMapper objectMapper;

    public ScanSummaryDTO getLatestScanSummary() {
        ScanRun run = scanRunRepository.findFirstByOrderByStartedAtDesc()
                .orElseThrow(() -> new IllegalArgumentException("No scan runs found"));
        return buildSummary(run);
    }

    public Optional<ScanSummaryDTO> findLatestScanSummary() {
        return scanRunRepository.findFirstByOrderByStartedAtDesc().map(this::buildSummary);
    }

    public ScanSummaryDTO getScanSummary(UUID scanRunId) {
        ScanRun run = scanRunRepository.findById(scanRunId)
                .orElseThrow(() -> new IllegalArgumentException("Scan run not found: " + scanRunId));
        return buildSummary(run);
    }

    public Page<ScanSummaryDTO> getHistoricalScans(int limit, int offset) {
        int page = limit > 0 ? offset / limit : 0;
        PageRequest pageRequest = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "startedAt"));
        return scanRunRepository.findAll(pageRequest).map(this::buildSummary);
    }

    public ScanReplayDTO getReplay(UUID scanRunId) {
        ScanSummaryDTO summary = getScanSummary(scanRunId);

        List<ScanPhaseDTO> phases = phaseEventRepository.findByScanRunIdOrderByStartedAtAsc(scanRunId).stream()
                .map(ev -> {
                    ScanPhaseDTO p = new ScanPhaseDTO();
                    p.setName(ev.getPhase());
                    p.setStatus(ev.getStatus());
                    p.setStartedAt(ev.getStartedAt());
                    p.setMetadata(parseJson(ev.getMetaJson()));
                    return p;
                }).toList();

        List<BestCandidateEventDTO> bestEvents = bestCandidateEventRepository.findByScanRunIdOrderByTsAsc(scanRunId)
                .stream()
                .map(this::toBestCandidateEventDTO)
                .toList();

        List<SymbolEvaluation> allEvals = evaluationRepository.findByScanRunId(scanRunId);
        List<SymbolEvaluationRowDTO> evalRows = allEvals.stream().map(this::toRowDTO).toList();

        ScanChartsDTO charts = buildCharts(allEvals);

        ScanReplayDTO dto = new ScanReplayDTO();
        dto.setSummary(summary);
        dto.setPhases(phases);
        dto.setBestCandidateEvents(bestEvents);
        dto.setEvaluations(evalRows);
        dto.setCharts(charts);
        return dto;
    }

    public Page<SymbolEvaluationRowDTO> getEvaluations(UUID scanRunId, String decision, String q, String sort,
            int limit, int offset) {
        int page = limit > 0 ? offset / limit : 0;
        PageRequest pageRequest = PageRequest.of(page, limit, Sort.by(Sort.Direction.ASC, "rankInUniverse"));

        boolean hasDecision = decision != null && !decision.equalsIgnoreCase("ALL");
        boolean hasQ = q != null && !q.isBlank();

        Page<SymbolEvaluation> raw;
        if (hasDecision || hasQ) {
            raw = evaluationRepository.search(
                    scanRunId,
                    hasDecision ? decision.toUpperCase() : null,
                    hasQ ? q : null,
                    pageRequest);
        } else {
            raw = evaluationRepository.findByScanRunId(scanRunId, pageRequest);
        }
        return raw.map(this::toRowDTO);
    }

    public SymbolEvaluationDetailDTO getEvaluationDetail(UUID scanRunId, String symbol) {
        SymbolEvaluation ev = evaluationRepository.findByScanRunIdAndSymbol(scanRunId, symbol)
                .orElseThrow(() -> new IllegalArgumentException("Symbol not found: " + symbol));
        return toDetailDTO(ev);
    }

    public ExplanationDTO getExplanation(UUID scanRunId, String symbol) {
        SymbolEvaluation ev = evaluationRepository.findByScanRunIdAndSymbol(scanRunId, symbol)
                .orElseThrow(() -> new IllegalArgumentException("Symbol not found: " + symbol));
        return explainabilityService.explain(ev);
    }

    public ScanChartsDTO getCharts(UUID scanRunId) {
        List<SymbolEvaluation> all = evaluationRepository.findByScanRunId(scanRunId);
        return buildCharts(all);
    }

    public ScanChartsDTO buildCharts(List<SymbolEvaluation> rows) {
        ScanChartsDTO dto = new ScanChartsDTO();

        List<Double> rrValues = new ArrayList<>();
        List<Double> confValues = new ArrayList<>();
        List<ScanChartsDTO.ScatterPoint> scatter = new ArrayList<>();
        Map<String, Integer> skipBreakdown = new TreeMap<>();
        Map<String, Integer> biasBreakdown = new TreeMap<>();

        for (SymbolEvaluation ev : rows) {
            if (ev.getBias() != null) {
                biasBreakdown.put(ev.getBias(), biasBreakdown.getOrDefault(ev.getBias(), 0) + 1);
            }

            Map<String, Object> metrics = parseJson(ev.getMetricsJson());

            if ("VALID".equals(ev.getDecision()) && metrics != null) {
                Double rr = toDouble(metrics.get("rr_tp1"));
                Double conf = toDouble(metrics.get("confidence_score"));
                Double score = toDouble(metrics.get("final_score"));

                if (rr != null)
                    rrValues.add(rr);
                if (conf != null)
                    confValues.add(conf);
                if (scatter.size() < 300) {
                    scatter.add(new ScanChartsDTO.ScatterPoint(ev.getSymbol(), rr, conf, score));
                }
            } else if ("NO_TRADE".equals(ev.getDecision()) && ev.getSkipReasonCode() != null) {
                String code = ev.getSkipReasonCode();
                skipBreakdown.put(code, skipBreakdown.getOrDefault(code, 0) + 1);
            }
        }

        dto.setRrHist(buildHistogram(rrValues, 10));
        dto.setConfHist(buildHistogram(confValues, 10));
        dto.setScatterPoints(scatter);
        dto.setSkipReasonBreakdown(skipBreakdown);
        dto.setBiasBreakdown(biasBreakdown);
        return dto;
    }

    private List<ScanChartsDTO.HistBin> buildHistogram(List<Double> values, int binCount) {
        if (values.isEmpty()) {
            return List.of();
        }
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        if (max == min)
            max = min + 1;
        double width = (max - min) / binCount;
        int[] bins = new int[binCount];
        for (double v : values) {
            int idx = Math.min((int) ((v - min) / width), binCount - 1);
            bins[idx]++;
        }
        List<ScanChartsDTO.HistBin> result = new ArrayList<>(binCount);
        for (int i = 0; i < binCount; i++) {
            double low = min + i * width;
            double high = low + width;
            result.add(new ScanChartsDTO.HistBin(low, high, bins[i]));
        }
        return result;
    }

    private ScanSummaryDTO buildSummary(ScanRun run) {
        ScanSummaryDTO dto = new ScanSummaryDTO();
        dto.setId(run.getId());
        dto.setStartedAt(run.getStartedAt());
        dto.setFinishedAt(run.getFinishedAt());
        dto.setStatus(run.getStatus());
        dto.setIntervalMinutes(run.getIntervalMinutes());
        dto.setTopN(run.getTopN());
        dto.setTriggerType(run.getTriggerType());
        dto.setErrorCode(run.getErrorCode());
        dto.setCorrelationId(run.getCorrelationId());
        dto.setNotes(run.getNotes());

        long evaluated = evaluationRepository.countByScanRunId(run.getId());
        long valid = evaluationRepository.countByScanRunIdAndDecision(run.getId(), "VALID");
        long noTrade = evaluationRepository.countByScanRunIdAndDecision(run.getId(), "NO_TRADE");
        dto.setEvaluatedCount(evaluated);
        dto.setValidCount(valid);
        dto.setNoTradeCount(noTrade);
        dto.setEligibleSymbolCount(run.getTopN());

        recommendationRepository.findFirstByScanRunIdOrderByCreatedAtDesc(run.getId())
                .ifPresent(rec -> dto.setBestRecommendationId(rec.getId()));

        List<ScanPhaseEvent> events = phaseEventRepository.findByScanRunIdOrderByStartedAtAsc(run.getId());
        dto.setPhases(events.stream().map(this::toPhaseDTO).toList());
        return dto;
    }

    private PhaseDTO toPhaseDTO(ScanPhaseEvent ev) {
        PhaseDTO p = new PhaseDTO();
        p.setPhase(ev.getPhase());
        p.setStatus(ev.getStatus());
        p.setStartedAt(ev.getStartedAt());
        p.setFinishedAt(ev.getFinishedAt());
        if (ev.getStartedAt() != null && ev.getFinishedAt() != null) {
            p.setDurationMs(Duration.between(ev.getStartedAt(), ev.getFinishedAt()).toMillis());
        }
        p.setMeta(parseJson(ev.getMetaJson()));
        return p;
    }

    private BestCandidateEventDTO toBestCandidateEventDTO(BestCandidateEvent ev) {
        BestCandidateEventDTO dto = new BestCandidateEventDTO();
        dto.setId(ev.getId());
        dto.setScanRunId(ev.getScanRunId());
        dto.setTs(ev.getTs());
        dto.setSymbol(ev.getSymbol());
        dto.setSide(ev.getSide());
        dto.setFinalScore(ev.getFinalScore());
        dto.setRecommendationId(ev.getRecommendationId());
        dto.setReasonJson(ev.getReasonJson());
        return dto;
    }

    private SymbolEvaluationRowDTO toRowDTO(SymbolEvaluation ev) {
        SymbolEvaluationRowDTO dto = new SymbolEvaluationRowDTO();
        populateRow(dto, ev);
        return dto;
    }

    private SymbolEvaluationDetailDTO toDetailDTO(SymbolEvaluation ev) {
        SymbolEvaluationDetailDTO dto = new SymbolEvaluationDetailDTO();
        populateRow(dto, ev);
        dto.setMetrics(parseJson(ev.getMetricsJson()));
        dto.setDiagnostics(parseJson(ev.getDiagnosticsJson()));
        return dto;
    }

    private void populateRow(SymbolEvaluationRowDTO dto, SymbolEvaluation ev) {
        dto.setSymbol(ev.getSymbol());
        dto.setDecision(ev.getDecision());
        dto.setSide(ev.getSide());
        dto.setBias(ev.getBias());
        dto.setRank(ev.getRankInUniverse());
        dto.setRankInUniverse(ev.getRankInUniverse());
        dto.setQuoteVolumeUsdt(ev.getQuoteVolumeUsdt());
        dto.setSkipReasonCode(ev.getSkipReasonCode());
        dto.setSkipReasonText(ev.getSkipReasonText());

        Map<String, Object> metrics = parseJson(ev.getMetricsJson());
        if (metrics != null) {
            dto.setFinalScore(toDouble(metrics.get("final_score")));
            dto.setConfidenceScore(toDouble(metrics.get("confidence_score")));
            dto.setConfidence(toDouble(metrics.get("confidence_score")));
            dto.setRrTp1(toDouble(metrics.get("rr_tp1")));
            Object entry = metrics.get("entry");
            Object sl = metrics.get("sl");
            Object tp1 = metrics.get("tp1");
            dto.setEntry(entry != null ? entry.toString() : null);
            dto.setSl(sl != null ? sl.toString() : null);
            dto.setTp1(tp1 != null ? tp1.toString() : null);
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank())
            return null;
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse JSON: {}", e.getMessage());
            return null;
        }
    }

    private Double toDouble(Object val) {
        if (val == null)
            return null;
        if (val instanceof Number n)
            return n.doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
