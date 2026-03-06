package com.tradebot.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.demo.entity.DemoAccountEquityEvent;
import com.tradebot.demo.entity.DemoAnalyticsSnapshot;
import com.tradebot.demo.entity.DemoTrade;
import com.tradebot.demo.repository.DemoAccountEquityEventRepository;
import com.tradebot.demo.repository.DemoAnalyticsSnapshotRepository;
import com.tradebot.demo.repository.DemoTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DemoAnalyticsService {

    private final DemoTradeRepository tradeRepository;
    private final DemoAccountEquityEventRepository equityEventRepository;
    private final DemoAnalyticsSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public AnalyticsResult getSummary(int lookback) {
        if (lookback != 10 && lookback != 50 && lookback != 100) {
            throw new IllegalArgumentException("lookback must be one of 10, 50, 100");
        }

        List<DemoTrade> tradesDesc = tradeRepository.findLastClosed(lookback);
        UUID lastTradeId = tradesDesc.isEmpty() ? null : tradesDesc.get(0).getId();
        if (lastTradeId != null) {
            var cached = snapshotRepository.findFirstByLookbackNAndLastTradeId(lookback, lastTradeId);
            if (cached.isPresent()) {
                try {
                    Map<String, Object> payload = objectMapper.readValue(
                            cached.get().getSummaryJson(), new TypeReference<>() {
                            });
                    return AnalyticsResult.fromPayload(lookback, payload);
                } catch (Exception ignored) {
                    log.debug("Failed to parse cached demo analytics snapshot, recomputing");
                }
            }
        }

        List<DemoTrade> trades = new ArrayList<>(tradesDesc);
        trades.sort(Comparator.comparing(DemoTrade::getClosedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, Object> metrics = computeMetrics(trades);
        Map<String, List<Map<String, Object>>> cohorts = computeCohorts(trades);
        List<Map<String, Object>> patterns = computeFailurePatterns(trades);
        Instant generatedAt = Instant.now();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", generatedAt.toString());
        payload.put("metrics", metrics);
        payload.put("cohorts", cohorts);
        payload.put("topFailurePatterns", patterns);

        if (lastTradeId != null) {
            persistSnapshot(lookback, lastTradeId, payload);
        }
        return new AnalyticsResult(lookback, generatedAt, metrics, cohorts, patterns);
    }

    @Transactional
    protected void persistSnapshot(int lookback, UUID lastTradeId, Map<String, Object> payload) {
        snapshotRepository.findFirstByLookbackNAndLastTradeId(lookback, lastTradeId)
                .ifPresentOrElse(existing -> {
                }, () -> {
                    DemoAnalyticsSnapshot snapshot = new DemoAnalyticsSnapshot();
                    snapshot.setCreatedAt(Instant.now());
                    snapshot.setLookbackN(lookback);
                    snapshot.setLastTradeId(lastTradeId);
                    snapshot.setSummaryJson(toJson(payload));
                    snapshotRepository.save(snapshot);
                });
    }

    private Map<String, Object> computeMetrics(List<DemoTrade> trades) {
        int total = trades.size();
        int wins = 0;
        int losses = 0;
        BigDecimal sumWinR = BigDecimal.ZERO;
        BigDecimal sumLossRAbs = BigDecimal.ZERO;
        int winRCount = 0;
        int lossRCount = 0;
        BigDecimal sumProfitPnl = BigDecimal.ZERO;
        BigDecimal sumLossPnlAbs = BigDecimal.ZERO;
        long totalHoldMinutes = 0L;
        int holdCount = 0;
        Map<String, Integer> reasonDistribution = new LinkedHashMap<>();
        int maxWinStreak = 0;
        int maxLossStreak = 0;
        int currentWinStreak = 0;
        int currentLossStreak = 0;

        int timeStopCount = 0;
        int timeStopWins = 0;
        BigDecimal timeStopPnl = BigDecimal.ZERO;
        BigDecimal timeStopR = BigDecimal.ZERO;
        int timeStopRCount = 0;

        for (DemoTrade trade : trades) {
            BigDecimal pnl = nz(trade.getPnlUsdt());
            BigDecimal r = trade.getRMultiple();

            boolean win = pnl.compareTo(BigDecimal.ZERO) > 0;
            boolean loss = pnl.compareTo(BigDecimal.ZERO) < 0;

            if (win) {
                wins++;
                currentWinStreak++;
                currentLossStreak = 0;
                if (r != null) {
                    sumWinR = sumWinR.add(r);
                    winRCount++;
                }
                sumProfitPnl = sumProfitPnl.add(pnl);
            } else if (loss) {
                losses++;
                currentLossStreak++;
                currentWinStreak = 0;
                if (r != null) {
                    sumLossRAbs = sumLossRAbs.add(r.abs());
                    lossRCount++;
                }
                sumLossPnlAbs = sumLossPnlAbs.add(pnl.abs());
            } else {
                currentWinStreak = 0;
                currentLossStreak = 0;
            }

            maxWinStreak = Math.max(maxWinStreak, currentWinStreak);
            maxLossStreak = Math.max(maxLossStreak, currentLossStreak);

            String reason = trade.getCloseReason();
            reasonDistribution.merge(normalizeCloseReason(reason), 1, Integer::sum);

            if (trade.getOpenedAt() != null && trade.getClosedAt() != null) {
                long hold = Duration.between(trade.getOpenedAt(), trade.getClosedAt()).toMinutes();
                if (hold >= 0) {
                    totalHoldMinutes += hold;
                    holdCount++;
                }
            }

            if ("TIME_STOP".equalsIgnoreCase(reason)) {
                timeStopCount++;
                if (win) {
                    timeStopWins++;
                }
                timeStopPnl = timeStopPnl.add(pnl);
                if (r != null) {
                    timeStopR = timeStopR.add(r);
                    timeStopRCount++;
                }
            }
        }

        BigDecimal winRate = ratio(wins, total);
        BigDecimal lossRate = ratio(losses, total);
        BigDecimal avgWinR = ratio(sumWinR, winRCount);
        BigDecimal avgLossR = ratio(sumLossRAbs, lossRCount);
        BigDecimal expectancyR = winRate.multiply(avgWinR).subtract(lossRate.multiply(avgLossR));
        BigDecimal profitFactor = sumLossPnlAbs.compareTo(BigDecimal.ZERO) > 0
                ? sumProfitPnl.divide(sumLossPnlAbs, 6, RoundingMode.HALF_UP)
                : null;
        BigDecimal avgHoldMinutes = ratio(BigDecimal.valueOf(totalHoldMinutes), holdCount);

        BigDecimal maxDrawdown = computeMaxDrawdown(trades);

        Map<String, Object> timeStopEffectiveness = new LinkedHashMap<>();
        timeStopEffectiveness.put("count", timeStopCount);
        timeStopEffectiveness.put("winRate", ratio(timeStopWins, timeStopCount));
        timeStopEffectiveness.put("avgPnlUsdt", ratio(timeStopPnl, timeStopCount));
        timeStopEffectiveness.put("avgR", ratio(timeStopR, timeStopRCount));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("total", total);
        metrics.put("wins", wins);
        metrics.put("losses", losses);
        metrics.put("winRate", winRate);
        metrics.put("avgWinR", avgWinR);
        metrics.put("avgLossR", avgLossR);
        metrics.put("expectancyR", expectancyR);
        metrics.put("profitFactor", profitFactor);
        metrics.put("maxDrawdownPct", maxDrawdown);
        metrics.put("avgHoldMinutes", avgHoldMinutes);
        metrics.put("closeReasonDistribution", reasonDistribution);
        metrics.put("maxWinStreak", maxWinStreak);
        metrics.put("maxLossStreak", maxLossStreak);
        metrics.put("timeStopEffectiveness", timeStopEffectiveness);
        return metrics;
    }

    private BigDecimal computeMaxDrawdown(List<DemoTrade> windowTrades) {
        Set<UUID> tradeIds = windowTrades.stream().map(DemoTrade::getId).collect(Collectors.toSet());
        List<DemoAccountEquityEvent> events = equityEventRepository.findAllByOrderByCreatedAtAsc().stream()
                .filter(e -> e.getTradeId() != null && tradeIds.contains(e.getTradeId()))
                .toList();

        if (events.isEmpty()) {
            BigDecimal peak = BigDecimal.ZERO;
            BigDecimal equity = BigDecimal.ZERO;
            BigDecimal maxDd = BigDecimal.ZERO;
            for (DemoTrade trade : windowTrades) {
                equity = equity.add(nz(trade.getPnlUsdt()));
                if (equity.compareTo(peak) > 0) {
                    peak = equity;
                }
                if (peak.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal dd = peak.subtract(equity).divide(peak, 6, RoundingMode.HALF_UP);
                    if (dd.compareTo(maxDd) > 0) {
                        maxDd = dd;
                    }
                }
            }
            return maxDd;
        }

        BigDecimal peak = null;
        BigDecimal maxDd = BigDecimal.ZERO;
        for (DemoAccountEquityEvent event : events) {
            BigDecimal equity = nz(event.getEquityUsdt());
            if (peak == null || equity.compareTo(peak) > 0) {
                peak = equity;
            }
            if (peak != null && peak.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal dd = peak.subtract(equity).divide(peak, 6, RoundingMode.HALF_UP);
                if (dd.compareTo(maxDd) > 0) {
                    maxDd = dd;
                }
            }
        }
        return maxDd;
    }

    private Map<String, List<Map<String, Object>>> computeCohorts(List<DemoTrade> trades) {
        List<TradeFeatures> features = trades.stream().map(this::extractFeatures).toList();
        Map<DemoTrade, String> atrBuckets = atrBuckets(features);

        Map<String, List<Map<String, Object>>> output = new LinkedHashMap<>();
        output.put("side", aggregate(features, f -> f.trade().getSide()));
        output.put("sweepDepth", aggregate(features, f -> bucketSweepDepth(f.sweepDepthRatio())));
        output.put("reclaimStrength", aggregate(features, f -> bucketReclaimStrength(f.reclaimStrength())));
        output.put("volatilityATR", aggregate(features, f -> atrBuckets.getOrDefault(f.trade(), "med")));
        output.put("session", aggregate(features, f -> bucketSession(f.trade().getOpenedAt())));
        output.put("rrAtEntry", aggregate(features, f -> bucketRrAtEntry(f.rrLiveAtEntry())));
        return output;
    }

    private List<Map<String, Object>> computeFailurePatterns(List<DemoTrade> trades) {
        List<TradeFeatures> features = trades.stream().map(this::extractFeatures).toList();
        Map<DemoTrade, String> atrBuckets = atrBuckets(features);
        Map<String, Aggregation> patterns = new HashMap<>();

        for (TradeFeatures f : features) {
            String pattern = "reclaim=" + bucketReclaimStrength(f.reclaimStrength())
                    + ", atr=" + atrBuckets.getOrDefault(f.trade(), "med");
            patterns.computeIfAbsent(pattern, ignored -> new Aggregation())
                    .add(f.trade());
        }

        return patterns.entrySet().stream()
                .filter(e -> e.getValue().count >= 3)
                .sorted((a, b) -> {
                    int byExpectancy = a.getValue().expectancyR().compareTo(b.getValue().expectancyR());
                    if (byExpectancy != 0) {
                        return byExpectancy;
                    }
                    int byWinRate = a.getValue().winRate().compareTo(b.getValue().winRate());
                    if (byWinRate != 0) {
                        return byWinRate;
                    }
                    return Integer.compare(b.getValue().count, a.getValue().count);
                })
                .limit(3)
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("pattern", e.getKey());
                    row.put("count", e.getValue().count);
                    row.put("winRate", e.getValue().winRate());
                    row.put("expectancyR", e.getValue().expectancyR());
                    row.put("hypothesis", hypothesisForPattern(e.getKey()));
                    return row;
                })
                .toList();
    }

    private Map<DemoTrade, String> atrBuckets(List<TradeFeatures> features) {
        List<BigDecimal> atrValues = features.stream()
                .map(TradeFeatures::atrRatio)
                .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) >= 0)
                .sorted()
                .toList();

        BigDecimal p33;
        BigDecimal p66;
        if (atrValues.size() < 6) {
            p33 = new BigDecimal("0.9");
            p66 = new BigDecimal("1.3");
        } else {
            p33 = percentile(atrValues, 0.33d);
            p66 = percentile(atrValues, 0.66d);
        }

        Map<DemoTrade, String> buckets = new HashMap<>();
        for (TradeFeatures f : features) {
            BigDecimal value = f.atrRatio();
            if (value.compareTo(p33) <= 0) {
                buckets.put(f.trade(), "low");
            } else if (value.compareTo(p66) >= 0) {
                buckets.put(f.trade(), "high");
            } else {
                buckets.put(f.trade(), "med");
            }
        }
        return buckets;
    }

    private BigDecimal percentile(List<BigDecimal> sorted, double p) {
        if (sorted.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int index = (int) Math.floor((sorted.size() - 1) * p);
        if (index < 0) {
            index = 0;
        }
        if (index >= sorted.size()) {
            index = sorted.size() - 1;
        }
        return sorted.get(index);
    }

    private List<Map<String, Object>> aggregate(List<TradeFeatures> features, java.util.function.Function<TradeFeatures, String> bucketFn) {
        Map<String, Aggregation> rows = new LinkedHashMap<>();
        for (TradeFeatures f : features) {
            String bucket = bucketFn.apply(f);
            if (bucket == null || bucket.isBlank()) {
                bucket = "unknown";
            }
            rows.computeIfAbsent(bucket, ignored -> new Aggregation()).add(f.trade());
        }
        return rows.entrySet().stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("bucket", e.getKey());
                    row.put("count", e.getValue().count);
                    row.put("wins", e.getValue().wins);
                    row.put("losses", e.getValue().losses);
                    row.put("winRate", e.getValue().winRate());
                    row.put("expectancyR", e.getValue().expectancyR());
                    return row;
                })
                .toList();
    }

    private TradeFeatures extractFeatures(DemoTrade trade) {
        JsonNode root = parseJson(trade.getSnapshotJson());
        JsonNode diagnostics = root.path("diagnostics");

        BigDecimal sweepDepthRatio = decimalOrDefault(diagnostics.path("sweepDepthRatio"), BigDecimal.ZERO);
        BigDecimal reclaimStrength = decimalOrDefault(diagnostics.path("reclaimStrength"), BigDecimal.ZERO);
        BigDecimal atrRatio = decimalOrDefault(diagnostics.path("atrRatio"), BigDecimal.ONE);
        BigDecimal rrLive = decimalOrDefault(root.path("rrLive"), DemoCandidateEvaluator.LOCKED_MIN_RR);
        boolean staleSetup = diagnostics.path("staleSetup").asBoolean(false);
        boolean timeStopHit = "TIME_STOP".equalsIgnoreCase(trade.getCloseReason());

        return new TradeFeatures(trade, sweepDepthRatio, reclaimStrength, atrRatio, rrLive, staleSetup, timeStopHit);
    }

    private String hypothesisForPattern(String pattern) {
        if (pattern.contains("reclaim=weak") && pattern.contains("atr=high")) {
            return "Weak reclaims during high volatility are likely noise reversals; tighten quality gating.";
        }
        if (pattern.contains("atr=high")) {
            return "High volatility cluster suggests more false continuation signals.";
        }
        if (pattern.contains("reclaim=weak")) {
            return "Weak reclaim momentum underperforms; favor stronger reclaim bodies.";
        }
        return "Loss cluster indicates setup-quality mismatch in this cohort.";
    }

    private String bucketSweepDepth(BigDecimal depth) {
        if (depth.compareTo(new BigDecimal("0.33")) < 0) {
            return "shallow";
        }
        if (depth.compareTo(new BigDecimal("0.66")) < 0) {
            return "medium";
        }
        return "deep";
    }

    private String bucketReclaimStrength(BigDecimal strength) {
        if (strength.compareTo(new BigDecimal("0.33")) < 0) {
            return "weak";
        }
        if (strength.compareTo(new BigDecimal("0.66")) < 0) {
            return "medium";
        }
        return "strong";
    }

    private String bucketSession(Instant openedAt) {
        if (openedAt == null) {
            return "unknown";
        }
        int hour = openedAt.atZone(ZoneOffset.UTC).getHour();
        if (hour <= 5) {
            return "00-05";
        }
        if (hour <= 11) {
            return "06-11";
        }
        if (hour <= 17) {
            return "12-17";
        }
        return "18-23";
    }

    private String bucketRrAtEntry(BigDecimal rr) {
        if (rr.compareTo(new BigDecimal("2.5")) < 0) {
            return "2-2.5";
        }
        if (rr.compareTo(new BigDecimal("3.0")) < 0) {
            return "2.5-3";
        }
        return "3+";
    }

    private String normalizeCloseReason(String reason) {
        if (reason == null) {
            return "OTHER";
        }
        Set<String> known = new HashSet<>(Set.of("TP1", "TP2", "TP3", "SL", "TIME_STOP"));
        return known.contains(reason) ? reason : "OTHER";
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private BigDecimal decimalOrDefault(JsonNode node, BigDecimal fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isTextual()) {
                return new BigDecimal(node.asText());
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return nz(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static class Aggregation {
        private int count;
        private int wins;
        private int losses;
        private BigDecimal winRSum = BigDecimal.ZERO;
        private int winRCount;
        private BigDecimal lossRSumAbs = BigDecimal.ZERO;
        private int lossRCount;

        private void add(DemoTrade trade) {
            count++;
            BigDecimal pnl = trade.getPnlUsdt() == null ? BigDecimal.ZERO : trade.getPnlUsdt();
            BigDecimal r = trade.getRMultiple();
            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                wins++;
                if (r != null) {
                    winRSum = winRSum.add(r);
                    winRCount++;
                }
            } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
                losses++;
                if (r != null) {
                    lossRSumAbs = lossRSumAbs.add(r.abs());
                    lossRCount++;
                }
            }
        }

        private BigDecimal winRate() {
            if (count <= 0) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
        }

        private BigDecimal expectancyR() {
            BigDecimal avgWinR = winRCount > 0 ? winRSum.divide(BigDecimal.valueOf(winRCount), 6, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal avgLossR = lossRCount > 0
                    ? lossRSumAbs.divide(BigDecimal.valueOf(lossRCount), 6, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal wr = winRate();
            return wr.multiply(avgWinR).subtract(BigDecimal.ONE.subtract(wr).multiply(avgLossR));
        }
    }

    private record TradeFeatures(
            DemoTrade trade,
            BigDecimal sweepDepthRatio,
            BigDecimal reclaimStrength,
            BigDecimal atrRatio,
            BigDecimal rrLiveAtEntry,
            boolean staleSetup,
            boolean timeStopHit) {
    }

    public record AnalyticsResult(
            int lookback,
            Instant generatedAt,
            Map<String, Object> metrics,
            Map<String, List<Map<String, Object>>> cohorts,
            List<Map<String, Object>> topFailurePatterns) {

        static AnalyticsResult fromPayload(int lookback, Map<String, Object> payload) {
            Instant generatedAt;
            try {
                generatedAt = Instant.parse(String.valueOf(payload.getOrDefault("generatedAt", Instant.now().toString())));
            } catch (Exception ignored) {
                generatedAt = Instant.now();
            }
            Map<String, Object> metrics = castMap(payload.get("metrics"));
            Map<String, List<Map<String, Object>>> cohorts = castCohorts(payload.get("cohorts"));
            List<Map<String, Object>> patterns = castPatternList(payload.get("topFailurePatterns"));
            return new AnalyticsResult(lookback, generatedAt, metrics, cohorts, patterns);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> castMap(Object value) {
            return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, List<Map<String, Object>>> castCohorts(Object value) {
            return value instanceof Map<?, ?> m ? (Map<String, List<Map<String, Object>>>) m : Map.of();
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> castPatternList(Object value) {
            return value instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        }
    }
}
