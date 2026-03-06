package com.tradebot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.config.AppProperties;
import com.tradebot.dto.BinanceExchangeInfoResponse;
import com.tradebot.dto.BinanceTicker24hResponse;
import com.tradebot.dto.Candle;
import com.tradebot.dto.StrategyTuningConfig;
import com.tradebot.entity.OrderFields;
import com.tradebot.entity.BestCandidateEvent;
import com.tradebot.entity.Recommendation;
import com.tradebot.entity.ScanPhaseEvent;
import com.tradebot.entity.ScanRun;
import com.tradebot.entity.SymbolEvaluation;
import com.tradebot.entity.SymbolUniverseSnapshot;
import com.tradebot.exception.ScanWorkerUnavailableException;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.BestCandidateEventRepository;
import com.tradebot.repository.ScanPhaseEventRepository;
import com.tradebot.repository.ScanRunRepository;
import com.tradebot.repository.SymbolEvaluationRepository;
import com.tradebot.repository.SymbolUniverseSnapshotRepository;
import com.tradebot.sse.ScanEventPublisher;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.awt.Toolkit;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScanOrchestrator {

    private static final int RUN_NOTES_MAX_LENGTH = 255;
    private static final int MAX_SCHEDULED_START_ATTEMPTS = 3;
    private static final long START_RETRY_BACKOFF_MS = 750L;
    private static final Duration STALE_RUN_TIMEOUT = Duration.ofMinutes(30);
    private static final String STATUS_STARTED = "STARTED";
    private static final String STATUS_FINISHED = "FINISHED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String TRIGGER_MANUAL = "MANUAL";
    private static final String TRIGGER_SCHEDULED = "SCHEDULED";
    private static final String ERROR_CODE_WORKER_RESTART = "WORKER_RESTART";
    private static final String ERROR_CODE_PIPELINE_FAILED = "SCAN_PIPELINE_FAILED";
    private static final String ERROR_CODE_WORKER_UNAVAILABLE = "SCANNER_DOWN";

    private final BinanceClient binanceClient;
    private final BiasDetector biasDetector;
    private final ImpulseLegDetector impulseDetector;
    private final FibonacciCalculator fibCalculator;
    private final SweepReclaimDetector sweepDetector;
    private final RiskAndSizingCalculator riskCalculator;

    private final ScanRunRepository scanRunRepository;
    private final SymbolUniverseSnapshotRepository snapshotRepository;
    private final RecommendationRepository recommendationRepository;
    private final ScanPhaseEventRepository phaseEventRepository;
    private final SymbolEvaluationRepository evaluationRepository;
    private final BestCandidateEventRepository bestCandidateEventRepository;
    private final StrategyConfigProvider strategyConfigProvider;
    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final ScanEventPublisher scanEventPublisher;
    @Qualifier("scanTaskExecutor")
    private final TaskExecutor scanTaskExecutor;

    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private final Object runStartMonitor = new Object();

    @PostConstruct
    public void reconcileStaleRunsOnStartup() {
        List<ScanRun> staleRuns = scanRunRepository.findByStatus(STATUS_STARTED);
        if (staleRuns.isEmpty()) {
            return;
        }
        for (ScanRun staleRun : staleRuns) {
            markRunFailed(staleRun, ERROR_CODE_WORKER_RESTART,
                    "Scan worker recovered after restart and marked stale run as failed.");
            log.warn("Marked stale in-progress run as failed after restart: runId={}", staleRun.getId());
        }
    }

    public boolean isScanRunning() {
        return scanRunning.get() || scanRunRepository.findFirstByStatusOrderByStartedAtDesc(STATUS_STARTED).isPresent();
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 10000)
    public void runScheduled() {
        ControlCenterConfig settings = controlCenterSettingsProvider.getConfigSnapshot();
        if (!settings.getScan().isAutoscanEnabled() || settings.getScan().isSafeMode()) {
            log.debug("Skipping scheduled scan: safeMode={}, autoscanEnabled={}",
                    settings.getScan().isSafeMode(), settings.getScan().isAutoscanEnabled());
            return;
        }
        String correlationId = UUID.randomUUID().toString();
        int interval = Math.max(settings.getScan().getIntervalMinutes(), 1);
        String dedupKey = scheduledWindowDedupKey(interval, Instant.now());
        for (int attempt = 1; attempt <= MAX_SCHEDULED_START_ATTEMPTS; attempt++) {
            try {
                ScanStartResult result = tryStartScheduledRun(settings, correlationId, dedupKey);
                if (result.startedNew()) {
                    log.info("Scheduled scan started: runId={}, attempt={}", result.scanRunId(), attempt);
                }
                return;
            } catch (DataAccessException | ScanWorkerUnavailableException ex) {
                if (attempt >= MAX_SCHEDULED_START_ATTEMPTS) {
                    log.error("Scheduled scan start failed after {} attempts: {}", attempt, ex.getMessage(), ex);
                    return;
                }
                log.warn("Scheduled scan start attempt {} failed: {}. Retrying with backoff.", attempt, ex.getMessage());
                sleepBackoff(attempt);
            }
        }
    }

    public UUID runOnce() {
        return runOnce(null).scanRunId();
    }

    public ScanStartResult runOnce(String correlationId) {
        ControlCenterConfig settings = controlCenterSettingsProvider.getConfigSnapshot();
        ScanStartResult result = tryStartRun(settings, TRIGGER_MANUAL, null, normalizeCorrelationId(correlationId));
        if (result.startedNew()) {
            log.info("Manual scan run started: runId={}", result.scanRunId());
        } else {
            log.info("Manual scan request deduplicated: runId={}, status={}", result.scanRunId(), result.status());
        }
        return result;
    }

    private ScanStartResult tryStartScheduledRun(ControlCenterConfig settings, String correlationId, String dedupKey) {
        return tryStartRun(settings, TRIGGER_SCHEDULED, dedupKey, normalizeCorrelationId(correlationId));
    }

    private ScanStartResult tryStartRun(ControlCenterConfig settings,
            String triggerType,
            String dedupKey,
            String correlationId) {
        synchronized (runStartMonitor) {
            ScanRun running = scanRunRepository.findFirstByStatusOrderByStartedAtDesc(STATUS_STARTED).orElse(null);
            if (running != null && isStaleRun(running)) {
                log.warn("Detected stale in-progress scan run. Reconciling to FAILED before starting a new run: runId={}, startedAt={}",
                        running.getId(), running.getStartedAt());
                markRunFailed(running, ERROR_CODE_WORKER_RESTART, "Recovered stale in-progress run before new start.");
                running = null;
            }
            if (running != null) {
                return new ScanStartResult(running.getId(), false, "ALREADY_RUNNING");
            }

            if (dedupKey != null) {
                ScanRun duplicate = scanRunRepository.findFirstByDedupKey(dedupKey).orElse(null);
                if (duplicate != null) {
                    return new ScanStartResult(duplicate.getId(), false, "ALREADY_SCHEDULED");
                }
            }

            ScanRun run;
            try {
                run = createStartedRun(settings, triggerType, dedupKey, correlationId);
            } catch (DataIntegrityViolationException ex) {
                ScanRun existing = scanRunRepository.findFirstByStatusOrderByStartedAtDesc(STATUS_STARTED)
                        .orElseGet(() -> dedupKey != null
                                ? scanRunRepository.findFirstByDedupKey(dedupKey).orElse(null)
                                : null);
                if (existing != null) {
                    return new ScanStartResult(existing.getId(), false, "ALREADY_RUNNING");
                }
                throw ex;
            }

            dispatchRun(run);
            return new ScanStartResult(run.getId(), true, STATUS_STARTED);
        }
    }

    private void dispatchRun(ScanRun run) {
        scanRunning.set(true);
        try {
            scanTaskExecutor.execute(() -> {
                String traceId = normalizeCorrelationId(run.getCorrelationId());
                try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", traceId)) {
                    runPipeline(run);
                } finally {
                    scanRunning.set(false);
                }
            });
        } catch (RejectedExecutionException ex) {
            scanRunning.set(false);
            markRunFailed(run, ERROR_CODE_WORKER_UNAVAILABLE, "Scan worker queue is full. Retry shortly.");
            throw new ScanWorkerUnavailableException("Scan worker queue is full", ex);
        }
    }

    private ScanRun createStartedRun(ControlCenterConfig settings,
            String triggerType,
            String dedupKey,
            String correlationId) {
        Instant now = Instant.now();
        ScanRun run = new ScanRun();
        run.setRequestedAt(now);
        run.setStartedAt(now);
        run.setIntervalMinutes(Math.max(settings.getScan().getIntervalMinutes(), 1));
        run.setTopN(appProperties.getScanner().getTopN());
        run.setStatus(STATUS_STARTED);
        run.setTriggerType(triggerType);
        run.setDedupKey(dedupKey);
        run.setCorrelationId(correlationId);
        run.setErrorCode(null);
        run.setNotes(null);
        run = scanRunRepository.save(run);
        final UUID scanRunId = run.getId();

        scanEventPublisher.publish(scanRunId, "scan.started", Map.of(
                "scanRunId", scanRunId.toString(),
                "startedAt", run.getStartedAt().toString(),
                "topN", run.getTopN(),
                "intervalMinutes", run.getIntervalMinutes(),
                "triggerType", run.getTriggerType()));
        return run;
    }

    private String scheduledWindowDedupKey(int intervalMinutes, Instant now) {
        long windowSeconds = Math.max(intervalMinutes, 1) * 60L;
        long startEpochSecond = (now.getEpochSecond() / windowSeconds) * windowSeconds;
        return "SCHEDULED:" + intervalMinutes + ":" + startEpochSecond;
    }

    private String normalizeCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return correlationId;
    }

    private void sleepBackoff(int attempt) {
        long sleepMs = START_RETRY_BACKOFF_MS * (1L << Math.max(attempt - 1, 0));
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isStaleRun(ScanRun run) {
        if (run == null || run.getStartedAt() == null) {
            return false;
        }
        return run.getStartedAt().plus(STALE_RUN_TIMEOUT).isBefore(Instant.now());
    }

    private void runPipeline(ScanRun run) {
        final UUID scanRunId = run.getId();

        ControlCenterConfig controlCenterConfig = controlCenterSettingsProvider.getConfigSnapshot();
        AppProperties runtimeProperties = buildRuntimeAppProperties(controlCenterConfig);
        StrategyTuningConfig tuningConfig = strategyConfigProvider.getActiveConfig();
        int validCount = 0;
        int noTradeCount = 0;

        try {
            log.info("Fetching exchange info...");
            Instant exchangeStart = Instant.now();
            BinanceExchangeInfoResponse exchangeInfo = binanceClient.getExchangeInfo();
            Map<String, BinanceExchangeInfoResponse.SymbolInfo> eligibleSymbolMap = exchangeInfo.getSymbols().stream()
                    .filter(s -> "PERPETUAL".equals(s.getContractType()))
                    .filter(s -> "USDT".equals(s.getQuoteAsset()))
                    .filter(s -> "TRADING".equals(s.getStatus()))
                    .collect(Collectors.toMap(BinanceExchangeInfoResponse.SymbolInfo::getSymbol, s -> s));

            int eligibleCount = eligibleSymbolMap.size();
            log.info("Found {} eligible USDT-M perpetual symbols", eligibleCount);
            emitPhase(scanRunId, "EXCHANGE_INFO", "FINISHED", exchangeStart,
                    Map.of("eligibleSymbolsCount", eligibleCount));

            log.info("Fetching 24h tickers...");
            Instant tickerStart = Instant.now();
            List<BinanceTicker24hResponse> tickers = binanceClient.getTicker24h();

            List<BinanceTicker24hResponse> topTickers = tickers.stream()
                    .filter(t -> eligibleSymbolMap.containsKey(t.getSymbol()))
                    .sorted(Comparator.comparing(BinanceTicker24hResponse::getQuoteVolume).reversed())
                    .limit(appProperties.getScanner().getTopN())
                    .toList();

            emitPhase(scanRunId, "TICKER_24H", "FINISHED", tickerStart,
                    Map.of("totalTickers", tickers.size(), "filteredCount", topTickers.size()));

            Instant universeStart = Instant.now();
            Instant snapshotTime = Instant.now();
            int rank = 1;
            for (BinanceTicker24hResponse t : topTickers) {
                SymbolUniverseSnapshot snap = new SymbolUniverseSnapshot();
                snap.setScanRunId(scanRunId);
                snap.setSymbol(t.getSymbol());
                snap.setRank(rank++);
                snap.setQuoteVolumeUsdt(t.getQuoteVolume());
                snap.setRecordedAt(snapshotTime);
                snapshotRepository.save(snap);
            }
            emitPhase(scanRunId, "UNIVERSE_TOP300", "FINISHED", universeStart, Map.of("topN", topTickers.size()));

            log.info("Processing klines for top {} symbols...", topTickers.size());
            Instant evalStart = Instant.now();
            Candidate bestCandidate = null;
            int evalRank = 1;

            for (BinanceTicker24hResponse t : topTickers) {
                String symbol = t.getSymbol();
                int symbolRank = evalRank++;
                try {
                    BinanceExchangeInfoResponse.SymbolInfo sInfo = eligibleSymbolMap.get(symbol);
                    if (sInfo == null) {
                        persistNoTrade(scanRunId, t, symbolRank, null, "DATA_ERROR", "No exchange info");
                        noTradeCount++;
                    } else {
                        List<Candle> execCandles = binanceClient.getKlines(symbol, "15m", 500);
                        List<Candle> biasCandles = binanceClient.getKlines(symbol, "1h", 500);

                        if (execCandles.size() < 50 || biasCandles.size() < 50) {
                            persistNoTrade(scanRunId, t, symbolRank, null, "DATA_ERROR", "Insufficient candles");
                            noTradeCount++;
                        } else {
                            BiasDetector.Bias bias = biasDetector.detectBias(biasCandles,
                                    runtimeProperties.getStrategy().getFractalPeriod());
                            if (bias == BiasDetector.Bias.UNKNOWN || bias == BiasDetector.Bias.RANGE) {
                                persistNoTrade(scanRunId, t, symbolRank, bias, "NO_BIAS", "Bias is " + bias);
                                noTradeCount++;
                            } else {
                                ImpulseLegDetector.ImpulseLeg impulse = impulseDetector.detectImpulseLeg(execCandles,
                                        bias,
                                        runtimeProperties.getStrategy().getFractalPeriod());
                                if (impulse == null) {
                                    persistNoTrade(scanRunId, t, symbolRank, bias, "NO_IMPULSE_BOS",
                                            "No impulse leg detected");
                                    noTradeCount++;
                                } else {
                                    FibonacciCalculator.FibLevels levels = fibCalculator.calculate(
                                            BigDecimal.valueOf(impulse.startPrice()),
                                            BigDecimal.valueOf(impulse.endPrice()));

                                    SweepReclaimDetector.Setup setup = sweepDetector.detect(execCandles,
                                            impulse.endIndex(), bias,
                                            levels, tuningConfig);
                                    if (setup == null) {
                                        persistNoTrade(scanRunId, t, symbolRank, bias, "NO_SWEEP",
                                                "No valid sweep/reclaim");
                                        noTradeCount++;
                                    } else {
                                        RiskAndSizingCalculator.EvaluationResult result = riskCalculator
                                                .calculateWithReason(
                                                        bias, setup, levels,
                                                        sInfo.getTickSize(), sInfo.getStepSize(), sInfo.getMinQty(),
                                                        runtimeProperties, tuningConfig);

                                        if (!result.isValid()) {
                                            persistNoTrade(scanRunId, t, symbolRank, bias, result.skipReasonCode(),
                                                    "Risk filter failed: " + result.skipReasonCode());
                                            noTradeCount++;
                                        } else {
                                            RiskAndSizingCalculator.ExecutionPlan plan = result.plan();
                                            persistValid(scanRunId, t, symbolRank, bias, plan, impulse, levels, setup);
                                            validCount++;

                                            if (bestCandidate == null
                                                    || plan.rrToTp1().compareTo(bestCandidate.plan.rrToTp1()) > 0) {
                                                bestCandidate = new Candidate(symbol, bias, plan);
                                                scanEventPublisher.publish(scanRunId, "recommendation.best", Map.of(
                                                        "scanRunId", scanRunId.toString(),
                                                        "symbol", symbol,
                                                        "side", bias == BiasDetector.Bias.UPTREND ? "LONG" : "SHORT",
                                                        "finalScore", plan.rrToTp1(),
                                                        "ts", Instant.now().toString()));

                                                // Save to historical replay timeline
                                                try {
                                                    BestCandidateEvent bce = new BestCandidateEvent();
                                                    bce.setScanRunId(scanRunId);
                                                    bce.setTs(Instant.now());
                                                    bce.setSymbol(symbol);
                                                    bce.setSide(bias == BiasDetector.Bias.UPTREND ? "LONG" : "SHORT");
                                                    bce.setFinalScore(plan.rrToTp1());
                                                    // Assuming scoreComponents can be derived or is part of plan
                                                    // For now, using a placeholder or actual plan details
                                                    Map<String, Object> scoreComponents = new HashMap<>();
                                                    scoreComponents.put("rrToTp1", plan.rrToTp1());
                                                    scoreComponents.put("entry", plan.entryPrice());
                                                    scoreComponents.put("sl", plan.slPrice());
                                                    scoreComponents.put("tp1", plan.tp1Price());
                                                    bce.setReasonJson(objectMapper.writeValueAsString(scoreComponents));
                                                    bestCandidateEventRepository.save(bce);
                                                } catch (Exception e) {
                                                    log.error("Failed to save best candidate event", e);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to process {}: {}", symbol, e.getMessage());
                    persistNoTrade(scanRunId, t, symbolRank, null, "DATA_ERROR", e.getMessage());
                    noTradeCount++;
                }

                if (symbolRank % 5 == 0 || symbolRank == topTickers.size()) {
                    scanEventPublisher.publish(scanRunId, "progress", Map.of(
                            "scanRunId", scanRunId.toString(),
                            "evaluated", symbolRank,
                            "total", topTickers.size(),
                            "validCount", validCount,
                            "noTradeCount", noTradeCount,
                            "ts", Instant.now().toString()));
                }
            }

            emitPhase(scanRunId, "STRATEGY_EVAL", "FINISHED", evalStart,
                    Map.of("evaluated", evalRank - 1, "validCount", validCount, "noTradeCount", noTradeCount));

            Instant rankStart = Instant.now();
            if (bestCandidate != null) {
                log.info("Best candidate found: {} {} (RR={})", bestCandidate.symbol, bestCandidate.bias,
                        bestCandidate.plan.rrToTp1());
                emitPhase(scanRunId, "RANKING", "FINISHED", rankStart,
                        Map.of("bestSymbol", bestCandidate.symbol, "bestRrTp1", bestCandidate.plan.rrToTp1()));

                Instant persistStart = Instant.now();
                saveRecommendation(run, bestCandidate);

                recommendationRepository.findFirstByScanRunIdOrderByCreatedAtDesc(scanRunId).ifPresent(rec -> {
                    emitPhase(scanRunId, "PERSIST", "FINISHED", persistStart,
                            Map.of("recommendationId", rec.getId().toString()));
                });
            } else {
                log.info("No valid trade setups found in this scan.");
                emitPhase(scanRunId, "RANKING", "FINISHED", rankStart, Map.of("validCount", 0));
            }

            emitPhase(scanRunId, "DONE", "FINISHED", Instant.now(), Map.of());

            run.setStatus(STATUS_FINISHED);
            run.setErrorCode(null);
            run.setNotes(null);
            run.setFinishedAt(Instant.now());
            scanRunRepository.save(run);

            scanEventPublisher.publish(scanRunId, "scan.finished", Map.of(
                    "scanRunId", scanRunId.toString(),
                    "finishedAt", run.getFinishedAt().toString(),
                    "status", STATUS_FINISHED,
                    "counts", Map.of("valid", validCount, "noTrade", noTradeCount)));

        } catch (Exception e) {
            String failureMessage = summarizeFailure(e);
            String notesMessage = truncateForNotes(failureMessage);
            log.error("Scan pipeline failed: {}", failureMessage, e);
            emitPhase(scanRunId, "FAILED", "FAILED", Instant.now(), Map.of("error", notesMessage));
            String errorCode = resolvePipelineErrorCode(e);
            markRunFailed(run, errorCode, notesMessage);
        }
    }

    static String summarizeFailure(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }

        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        String primary = safeMessage(error.getMessage(), error.getClass().getSimpleName());
        String rootMessage = safeMessage(root.getMessage(), root.getClass().getSimpleName());

        if (root != error && !primary.contains(rootMessage)) {
            return primary + " | rootCause=" + root.getClass().getSimpleName() + ": " + rootMessage;
        }
        return primary;
    }

    private static String safeMessage(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message;
    }

    private static String truncateForNotes(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown error";
        }
        if (value.length() <= RUN_NOTES_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, RUN_NOTES_MAX_LENGTH - 3) + "...";
    }

    private String resolvePipelineErrorCode(Throwable error) {
        if (error instanceof DataAccessException) {
            return "DB_DOWN";
        }
        String summary = summarizeFailure(error).toLowerCase();
        if (summary.contains("timeout") || summary.contains("timed out")) {
            return "UPSTREAM_TIMEOUT";
        }
        if (summary.contains("binance")) {
            return "BINANCE_UPSTREAM";
        }
        return ERROR_CODE_PIPELINE_FAILED;
    }

    private void markRunFailed(ScanRun run, String errorCode, String notesMessage) {
        String safeNotes = truncateForNotes(notesMessage);
        run.setStatus(STATUS_FAILED);
        run.setErrorCode(errorCode);
        run.setNotes(safeNotes);
        run.setFinishedAt(Instant.now());
        scanRunRepository.save(run);

        scanEventPublisher.publish(run.getId(), "scan.failed", Map.of(
                "scanRunId", run.getId().toString(),
                "finishedAt", run.getFinishedAt().toString(),
                "status", STATUS_FAILED,
                "errorCode", errorCode,
                "error", safeNotes));
    }

    private AppProperties buildRuntimeAppProperties(ControlCenterConfig controlCenterConfig) {
        AppProperties runtime = new AppProperties();
        runtime.setLeverage(appProperties.getLeverage());
        runtime.setWorkingType(appProperties.getWorkingType());
        runtime.getScanner().setTopN(appProperties.getScanner().getTopN());
        runtime.getScanner().setIntervalMinutes(controlCenterConfig.getScan().getIntervalMinutes());

        runtime.getBudget().setUsdt(controlCenterConfig.getRisk().getBudgetUsdt());

        runtime.getRisk().setMaxBudgetPct(controlCenterConfig.getRisk().getMaxBudgetPct());
        runtime.getRisk().setEquityOverrideUsdt(controlCenterConfig.getRisk().getEquityOverrideUsdt());
        runtime.getRisk().setMaxEquityPct(controlCenterConfig.getRisk().getMaxEquityPctLocked());

        runtime.getStrategy().setFractalPeriod(controlCenterConfig.getStrategyLocks().getFractalPeriod());
        runtime.getStrategy().setMinRr(controlCenterConfig.getStrategyLocks().getMinRr());
        runtime.getStrategy().setMaxSweepCandles(appProperties.getStrategy().getMaxSweepCandles());
        runtime.getStrategy().setInvalidationCandles(appProperties.getStrategy().getInvalidationCandles());
        runtime.getStrategy().setSlBufferPct(appProperties.getStrategy().getSlBufferPct());
        return runtime;
    }

    private void persistNoTrade(UUID scanRunId, BinanceTicker24hResponse ticker, int rank,
            BiasDetector.Bias bias, String skipCode, String skipText) {
        SymbolEvaluation ev = new SymbolEvaluation();
        ev.setScanRunId(scanRunId);
        ev.setSymbol(ticker.getSymbol());
        ev.setRankInUniverse(rank);
        ev.setQuoteVolumeUsdt(ticker.getQuoteVolume());
        ev.setBias(bias != null ? bias.name() : null);
        ev.setDecision("NO_TRADE");
        ev.setSide("NONE");
        ev.setSkipReasonCode(skipCode);
        ev.setSkipReasonText(skipText != null && skipText.length() > 255 ? skipText.substring(0, 255) : skipText);
        ev.setCreatedAt(Instant.now());
        evaluationRepository.save(ev);

        scanEventPublisher.publish(scanRunId, "symbol.evaluated", Map.of(
                "scanRunId", scanRunId.toString(),
                "symbol", ticker.getSymbol(),
                "rankInUniverse", rank,
                "decision", "NO_TRADE",
                "side", "NONE",
                "skipReasonCode", skipCode,
                "ts", ev.getCreatedAt().toString()));
    }

    private void persistValid(UUID scanRunId, BinanceTicker24hResponse ticker, int rank,
            BiasDetector.Bias bias, RiskAndSizingCalculator.ExecutionPlan plan,
            ImpulseLegDetector.ImpulseLeg impulse, FibonacciCalculator.FibLevels levels,
            SweepReclaimDetector.Setup setup) {
        SymbolEvaluation ev = new SymbolEvaluation();
        ev.setScanRunId(scanRunId);
        ev.setSymbol(ticker.getSymbol());
        ev.setRankInUniverse(rank);
        ev.setQuoteVolumeUsdt(ticker.getQuoteVolume());
        ev.setBias(bias.name());
        ev.setDecision("VALID");
        ev.setSide(bias == BiasDetector.Bias.UPTREND ? "LONG" : "SHORT");
        ev.setCreatedAt(Instant.now());

        try {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("rr_tp1", plan.rrToTp1());
            metrics.put("final_score", plan.rrToTp1());
            metrics.put("confidence_score", plan.rrToTp1());
            metrics.put("entry", plan.entryPrice());
            metrics.put("sl", plan.slPrice());
            metrics.put("tp1", plan.tp1Price());
            metrics.put("tp2", plan.tp2Price());
            metrics.put("tp3", plan.tp3Price());
            metrics.put("quantity", plan.quantity());
            metrics.put("leverage", appProperties.getLeverage());
            ev.setMetricsJson(objectMapper.writeValueAsString(metrics));

            Map<String, Object> diag = new HashMap<>();
            diag.put("impulseStartIndex", impulse.startIndex());
            diag.put("impulseEndIndex", impulse.endIndex());
            diag.put("impulseStartPrice", impulse.startPrice());
            diag.put("impulseEndPrice", impulse.endPrice());
            diag.put("fibZero", levels.zero());
            diag.put("fibFifty", levels.fifty());
            diag.put("fibGolden", levels.golden());
            diag.put("fibHundred", levels.hundred());
            diag.put("sweepPrice", setup.sweepPrice());
            ev.setDiagnosticsJson(objectMapper.writeValueAsString(diag));
        } catch (Exception e) {
            log.warn("Failed to serialize metrics/diagnostics for {}: {}", ticker.getSymbol(), e.getMessage());
        }

        evaluationRepository.save(ev);

        Map<String, Object> payload = new HashMap<>();
        payload.put("scanRunId", scanRunId.toString());
        payload.put("symbol", ticker.getSymbol());
        payload.put("rankInUniverse", rank);
        payload.put("decision", "VALID");
        payload.put("side", ev.getSide());
        payload.put("finalScore", plan.rrToTp1());
        payload.put("confidence", plan.rrToTp1());
        payload.put("rrTp1", plan.rrToTp1());
        payload.put("entry", plan.entryPrice());
        payload.put("sl", plan.slPrice());
        payload.put("tp1", plan.tp1Price());
        payload.put("ts", ev.getCreatedAt().toString());

        scanEventPublisher.publish(scanRunId, "symbol.evaluated", payload);
    }

    private void emitPhase(UUID scanRunId, String phase, String status, Instant startedAt, Map<String, Object> meta) {
        ScanPhaseEvent ev = new ScanPhaseEvent();
        ev.setScanRunId(scanRunId);
        ev.setPhase(phase);
        ev.setStatus(status);
        ev.setStartedAt(startedAt);
        ev.setFinishedAt(Instant.now());
        try {
            ev.setMetaJson(objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            log.warn("Failed to serialize phase meta: {}", e.getMessage());
        }
        phaseEventRepository.save(ev);

        scanEventPublisher.publish(scanRunId, "FAILED".equals(status) ? "phase.failed" : "phase.finished", Map.of(
                "scanRunId", scanRunId.toString(),
                "phase", phase,
                "status", status,
                "ts", ev.getFinishedAt().toString(),
                "meta", meta));
    }

    private void saveRecommendation(ScanRun run, Candidate bestCandidate) throws Exception {
        Recommendation lastRec = recommendationRepository.findFirstByOrderByCreatedAtDesc().orElse(null);

        Recommendation rec = new Recommendation();
        rec.setScanRun(run);
        rec.setSymbol(bestCandidate.symbol);
        String binanceSide = (bestCandidate.bias == BiasDetector.Bias.UPTREND) ? "BUY" : "SELL";
        String oppositeSide = "BUY".equals(binanceSide) ? "SELL" : "BUY";
        rec.setSide(binanceSide);

        String rationale = String.format("SM-Fib %s setup. Entry: %s, SL: %s, TP1: %s. R:R=%.2f",
                bestCandidate.bias,
                bestCandidate.plan.entryPrice(),
                bestCandidate.plan.slPrice(),
                bestCandidate.plan.tp1Price(),
                bestCandidate.plan.rrToTp1());

        rec.setRationaleText(rationale);
        rec.setConfidenceScore(bestCandidate.plan.rrToTp1());
        rec.setCreatedAt(Instant.now());
        rec.setStatus("NEW");

        OrderFields fields = new OrderFields();
        fields.setRecommendation(rec);
        fields.setRecommendationId(rec.getId());

        Map<String, Object> entry = new HashMap<>();
        entry.put("symbol", bestCandidate.symbol);
        entry.put("side", binanceSide);
        entry.put("type", "MARKET");
        entry.put("positionSide", "ONE_WAY");
        entry.put("quantity", bestCandidate.plan.quantity().toPlainString());
        fields.setEntryOrderJson(objectMapper.writeValueAsString(entry));

        Map<String, Object> sl = new HashMap<>();
        sl.put("symbol", bestCandidate.symbol);
        sl.put("side", oppositeSide);
        sl.put("type", "STOP_MARKET");
        sl.put("stopPrice", bestCandidate.plan.slPrice().toPlainString());
        sl.put("closePosition", true);
        sl.put("reduceOnly", true);
        sl.put("workingType", appProperties.getWorkingType());
        fields.setSlOrderJson(objectMapper.writeValueAsString(sl));

        Map<String, Object> tp = new HashMap<>();
        tp.put("symbol", bestCandidate.symbol);
        tp.put("side", oppositeSide);
        tp.put("type", "TAKE_PROFIT_MARKET");
        tp.put("stopPrice", bestCandidate.plan.tp1Price().toPlainString());
        tp.put("closePosition", true);
        tp.put("reduceOnly", true);
        tp.put("workingType", appProperties.getWorkingType());
        fields.setTpOrderJson(objectMapper.writeValueAsString(tp));

        fields.setLeverageRecommendation(appProperties.getLeverage());
        fields.setMarginMode("ISOLATED");
        fields.setPositionMode("ONE_WAY");

        rec.setOrderFields(fields);
        recommendationRepository.save(rec);

        if (lastRec == null || !lastRec.getSymbol().equals(rec.getSymbol())
                || !lastRec.getSide().equals(rec.getSide())) {
            log.info("NEW Best Recommendation surfaced. Beeping...");
            Toolkit.getDefaultToolkit().beep();
        }
    }

    public record ScanStartResult(UUID scanRunId, boolean startedNew, String status) {
    }

    private static class Candidate {
        final String symbol;
        final BiasDetector.Bias bias;
        final RiskAndSizingCalculator.ExecutionPlan plan;

        Candidate(String symbol, BiasDetector.Bias bias, RiskAndSizingCalculator.ExecutionPlan plan) {
            this.symbol = symbol;
            this.bias = bias;
            this.plan = plan;
        }
    }
}
