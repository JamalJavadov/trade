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
import com.tradebot.service.scan.DeepScanCandidateResult;
import com.tradebot.service.scan.DeepScanPipelineService;
import com.tradebot.service.scan.DeepScanStage;
import com.tradebot.service.scan.FrozenSymbolSnapshot;
import com.tradebot.service.scan.MarketSnapshotService;
import com.tradebot.service.scan.ScanCandidateEventService;
import com.tradebot.service.scan.StageAudit;
import com.tradebot.service.scan.StageFinding;
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
import java.util.LinkedHashMap;
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
    private final MarketSnapshotService marketSnapshotService;
    private final DeepScanPipelineService deepScanPipelineService;
    private final ScanCandidateEventService scanCandidateEventService;
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
        final String traceId = normalizeCorrelationId(run.getCorrelationId());

        ControlCenterConfig controlCenterConfig = controlCenterSettingsProvider.getConfigSnapshot();
        AppProperties runtimeProperties = buildRuntimeAppProperties(controlCenterConfig);
        StrategyTuningConfig tuningConfig = strategyConfigProvider.getActiveConfig();
        int validCount = 0;
        int noTradeCount = 0;
        int eligibleRecommendationCount = 0;
        int dataErrorCount = 0;

        try {
            log.info("Fetching exchange info...");
            Instant exchangeStart = Instant.now();
            emitPhaseStarted(scanRunId, "EXCHANGE_INFO", Map.of("traceId", traceId));
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
            emitPhaseStarted(scanRunId, "TICKER_24H", Map.of("eligibleSymbolsCount", eligibleCount));
            List<BinanceTicker24hResponse> tickers = binanceClient.getTicker24h();

            List<BinanceTicker24hResponse> topTickers = tickers.stream()
                    .filter(t -> eligibleSymbolMap.containsKey(t.getSymbol()))
                    .sorted(Comparator.comparing(BinanceTicker24hResponse::getQuoteVolume).reversed())
                    .limit(appProperties.getScanner().getTopN())
                    .toList();

            emitPhase(scanRunId, "TICKER_24H", "FINISHED", tickerStart,
                    Map.of("totalTickers", tickers.size(), "filteredCount", topTickers.size()));

            Instant universeStart = Instant.now();
            emitPhaseStarted(scanRunId, "UNIVERSE_TOP300", Map.of("topN", topTickers.size()));
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
            emitPhaseStarted(scanRunId, "STRATEGY_EVAL", Map.of("topN", topTickers.size()));
            Candidate bestCandidate = null;
            int evalRank = 1;

            for (BinanceTicker24hResponse t : topTickers) {
                String symbol = t.getSymbol();
                int symbolRank = evalRank++;
                try {
                    BinanceExchangeInfoResponse.SymbolInfo sInfo = eligibleSymbolMap.get(symbol);
                    List<Candle> execCandles = sInfo == null
                            ? List.of()
                            : binanceClient.getKlines(symbol, controlCenterConfig.getStrategyLocks().getExecutionTf(), 500);
                    List<Candle> biasCandles = sInfo == null
                            ? List.of()
                            : binanceClient.getKlines(symbol, controlCenterConfig.getStrategyLocks().getBiasTf(), 500);

                    FrozenSymbolSnapshot snapshot = marketSnapshotService.freeze(
                            scanRunId,
                            traceId,
                            t,
                            symbolRank,
                            sInfo,
                            execCandles,
                            biasCandles,
                            controlCenterConfig);
                    DeepScanCandidateResult candidateResult = deepScanPipelineService.evaluate(
                            snapshot,
                            runtimeProperties,
                            tuningConfig,
                            scanCandidateEventService.recorder(scanRunId, symbol));
                    persistCandidateEvaluation(scanRunId, t, symbolRank, candidateResult);

                    if ("VALID".equalsIgnoreCase(candidateResult.deterministicEvidence().rawDecision())) {
                        validCount++;
                    } else {
                        noTradeCount++;
                    }
                    if ("DATA_ERROR".equalsIgnoreCase(candidateResult.deterministicEvidence().skipReasonCode())) {
                        dataErrorCount++;
                    }
                    if (candidateResult.finalGate().eligible()) {
                        eligibleRecommendationCount++;
                        BigDecimal score = toBigDecimal(candidateResult.deterministicEvidence().metrics().get("rr_tp1"));
                        if (score != null && (bestCandidate == null || score.compareTo(bestCandidate.plan.rrToTp1()) > 0)) {
                            bestCandidate = new Candidate(symbol,
                                    candidateResult.deterministicEvidence().side(),
                                    candidateResult.deterministicEvidence().bias(),
                                    candidateResult);
                            publishBestCandidate(scanRunId, bestCandidate);
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to process {}: {}", symbol, e.getMessage());
                    persistUnexpectedFailure(scanRunId, traceId, t, symbolRank, e);
                    noTradeCount++;
                    dataErrorCount++;
                }

                if (symbolRank % 5 == 0 || symbolRank == topTickers.size()) {
                    scanEventPublisher.publish(scanRunId, "progress", Map.of(
                            "scanRunId", scanRunId.toString(),
                            "evaluated", symbolRank,
                            "total", topTickers.size(),
                            "validCount", validCount,
                            "noTradeCount", noTradeCount,
                            "eligibleCount", eligibleRecommendationCount,
                            "dataErrorCount", dataErrorCount,
                            "ts", Instant.now().toString()));
                }
            }

            emitPhase(scanRunId, "STRATEGY_EVAL", "FINISHED", evalStart,
                    Map.of(
                            "evaluated", evalRank - 1,
                            "validCount", validCount,
                            "noTradeCount", noTradeCount,
                            "eligibleCount", eligibleRecommendationCount,
                            "dataErrorCount", dataErrorCount));

            Instant rankStart = Instant.now();
            emitPhaseStarted(scanRunId, "RANKING", Map.of("eligibleCount", eligibleRecommendationCount));
            if (bestCandidate != null) {
                log.info("Best candidate found: {} {} (RR={})", bestCandidate.symbol, bestCandidate.side,
                        bestCandidate.plan.rrToTp1());
                emitPhase(scanRunId, "RANKING", "FINISHED", rankStart,
                        Map.of("bestSymbol", bestCandidate.symbol, "bestRrTp1", bestCandidate.plan.rrToTp1()));

                Instant persistStart = Instant.now();
                emitPhaseStarted(scanRunId, "PERSIST", Map.of("bestSymbol", bestCandidate.symbol));
                saveRecommendation(run, bestCandidate);

                recommendationRepository.findFirstByScanRunIdOrderByCreatedAtDesc(scanRunId).ifPresent(rec -> {
                    emitPhase(scanRunId, "PERSIST", "FINISHED", persistStart,
                            Map.of("recommendationId", rec.getId().toString()));
                });
            } else {
                log.info("No valid trade setups found in this scan.");
                emitPhase(scanRunId, "RANKING", "FINISHED", rankStart, Map.of("validCount", 0));
            }

            emitPhaseStarted(scanRunId, "DONE", Map.of("eligibleCount", eligibleRecommendationCount));
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
                    "counts", Map.of(
                            "valid", validCount,
                            "noTrade", noTradeCount,
                            "eligible", eligibleRecommendationCount,
                            "dataError", dataErrorCount)));

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

    private void persistCandidateEvaluation(
            UUID scanRunId,
            BinanceTicker24hResponse ticker,
            int rank,
            DeepScanCandidateResult candidateResult) {
        SymbolEvaluation ev = new SymbolEvaluation();
        ev.setScanRunId(scanRunId);
        ev.setSymbol(ticker.getSymbol());
        ev.setRankInUniverse(rank);
        ev.setQuoteVolumeUsdt(ticker.getQuoteVolume());
        ev.setBias(candidateResult.deterministicEvidence().bias());
        ev.setDecision(candidateResult.deterministicEvidence().rawDecision());
        ev.setSide(candidateResult.deterministicEvidence().side());
        ev.setSkipReasonCode(candidateResult.deterministicEvidence().skipReasonCode());
        ev.setSkipReasonText(truncateSkipReason(candidateResult.deterministicEvidence().skipReasonText()));
        ev.setTraceId(candidateResult.traceId());
        ev.setCreatedAt(candidateResult.completedAt());
        ev.setRecommendationEligible(candidateResult.finalGate().eligible());
        ev.setFinalIntegrityScore(candidateResult.finalGate().finalIntegrityScore());
        ev.setConflictState(candidateResult.conflictReport().state());

        ev.setMetricsJson(writeJson(candidateResult.deterministicEvidence().metrics()));
        ev.setDiagnosticsJson(writeJson(candidateResult.deterministicEvidence().diagnostics()));
        ev.setSnapshotJson(writeJson(candidateResult.snapshotSummary()));
        ev.setIntegrityJson(writeJson(toAuditJson(candidateResult.dataIntegrity())));
        ev.setDeterministicEvidenceJson(writeJson(candidateResult.deterministicEvidence()));
        ev.setValidationJson(writeJson(toAuditJson(candidateResult.structuralValidation())));
        ev.setConfirmationJson(writeJson(Map.of(
                "audit", toAuditJson(candidateResult.secondPassConfirmation()),
                "report", candidateResult.conflictReport())));
        ev.setAiReviewJson(writeJson(candidateResult.aiReview()));
        ev.setFinalGateJson(writeJson(candidateResult.finalGate()));

        evaluationRepository.save(ev);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scanRunId", scanRunId.toString());
        payload.put("symbol", ticker.getSymbol());
        payload.put("rankInUniverse", rank);
        payload.put("decision", ev.getDecision());
        payload.put("side", ev.getSide());
        payload.put("bias", ev.getBias());
        payload.put("skipReasonCode", ev.getSkipReasonCode());
        payload.put("skipReasonText", ev.getSkipReasonText());
        payload.put("finalScore", candidateResult.deterministicEvidence().metrics().get("final_score"));
        payload.put("confidence", candidateResult.deterministicEvidence().metrics().get("confidence_score"));
        payload.put("rrTp1", candidateResult.deterministicEvidence().metrics().get("rr_tp1"));
        payload.put("entry", candidateResult.deterministicEvidence().metrics().get("entry"));
        payload.put("sl", candidateResult.deterministicEvidence().metrics().get("sl"));
        payload.put("tp1", candidateResult.deterministicEvidence().metrics().get("tp1"));
        payload.put("traceId", candidateResult.traceId());
        payload.put("recommendationEligible", candidateResult.finalGate().eligible());
        payload.put("finalIntegrityScore", candidateResult.finalGate().finalIntegrityScore());
        payload.put("conflictState", candidateResult.conflictReport().state());
        payload.put("aiAgreementState", candidateResult.aiReview().agreementState());
        payload.put("aiReviewStatus", candidateResult.aiReview().status());
        payload.put("rejectionReasons", candidateResult.finalGate().rejectionReasons());
        payload.put("ts", ev.getCreatedAt().toString());

        scanEventPublisher.publish(scanRunId, "symbol.evaluated", payload);
    }

    private void persistUnexpectedFailure(
            UUID scanRunId,
            String traceId,
            BinanceTicker24hResponse ticker,
            int rank,
            Exception error) {
        SymbolEvaluation ev = new SymbolEvaluation();
        ev.setScanRunId(scanRunId);
        ev.setSymbol(ticker.getSymbol());
        ev.setRankInUniverse(rank);
        ev.setQuoteVolumeUsdt(ticker.getQuoteVolume());
        ev.setDecision("NO_TRADE");
        ev.setSide("NONE");
        ev.setSkipReasonCode("DATA_ERROR");
        ev.setSkipReasonText(truncateSkipReason(summarizeFailure(error)));
        ev.setTraceId(traceId);
        ev.setCreatedAt(Instant.now());
        ev.setRecommendationEligible(false);
        ev.setFinalIntegrityScore(0);
        ev.setConflictState("UNEXPECTED_FAILURE");
        ev.setSnapshotJson(writeJson(Map.of(
                "traceId", traceId,
                "symbol", ticker.getSymbol(),
                "rankInUniverse", rank)));
        ev.setIntegrityJson(writeJson(Map.of(
                "stage", DeepScanStage.DATA_INTEGRITY.name(),
                "status", "FAILED",
                "findings", List.of(new StageFinding("UNEXPECTED_PIPELINE_FAILURE", "CRITICAL", summarizeFailure(error), Map.of())))));
        ev.setFinalGateJson(writeJson(Map.of(
                "eligible", false,
                "finalIntegrityScore", 0,
                "rejectionReasons", List.of("UNEXPECTED_PIPELINE_FAILURE"))));
        evaluationRepository.save(ev);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scanRunId", scanRunId.toString());
        payload.put("symbol", ticker.getSymbol());
        payload.put("rankInUniverse", rank);
        payload.put("decision", "NO_TRADE");
        payload.put("side", "NONE");
        payload.put("skipReasonCode", "DATA_ERROR");
        payload.put("skipReasonText", ev.getSkipReasonText());
        payload.put("finalIntegrityScore", 0);
        payload.put("recommendationEligible", false);
        payload.put("traceId", traceId);
        payload.put("ts", ev.getCreatedAt().toString());
        scanEventPublisher.publish(scanRunId, "symbol.evaluated", payload);
    }

    private void emitPhaseStarted(UUID scanRunId, String phase, Map<String, Object> meta) {
        scanEventPublisher.publish(scanRunId, "phase.started", Map.of(
                "scanRunId", scanRunId.toString(),
                "phase", phase,
                "status", STATUS_STARTED,
                "ts", Instant.now().toString(),
                "meta", meta == null ? Map.of() : meta));
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

    private void publishBestCandidate(UUID scanRunId, Candidate bestCandidate) {
        scanEventPublisher.publish(scanRunId, "recommendation.best", Map.of(
                "scanRunId", scanRunId.toString(),
                "symbol", bestCandidate.symbol,
                "side", bestCandidate.side,
                "finalScore", bestCandidate.plan.rrToTp1(),
                "integrityScore", bestCandidate.result.finalGate().finalIntegrityScore(),
                "ts", Instant.now().toString()));

        try {
            BestCandidateEvent bce = new BestCandidateEvent();
            bce.setScanRunId(scanRunId);
            bce.setTs(Instant.now());
            bce.setSymbol(bestCandidate.symbol);
            bce.setSide(bestCandidate.side);
            bce.setFinalScore(bestCandidate.plan.rrToTp1());

            Map<String, Object> scoreComponents = new LinkedHashMap<>();
            scoreComponents.put("rrToTp1", bestCandidate.plan.rrToTp1());
            scoreComponents.put("entry", bestCandidate.plan.entryPrice());
            scoreComponents.put("sl", bestCandidate.plan.slPrice());
            scoreComponents.put("tp1", bestCandidate.plan.tp1Price());
            scoreComponents.put("integrityScore", bestCandidate.result.finalGate().finalIntegrityScore());
            scoreComponents.put("aiAgreementState", bestCandidate.result.aiReview().agreementState());
            bce.setReasonJson(objectMapper.writeValueAsString(scoreComponents));
            bestCandidateEventRepository.save(bce);
        } catch (Exception e) {
            log.error("Failed to save best candidate event", e);
        }
    }

    private void saveRecommendation(ScanRun run, Candidate bestCandidate) throws Exception {
        Recommendation lastRec = recommendationRepository.findFirstByOrderByCreatedAtDesc().orElse(null);

        Recommendation rec = new Recommendation();
        rec.setScanRun(run);
        rec.setSymbol(bestCandidate.symbol);
        String binanceSide = "LONG".equalsIgnoreCase(bestCandidate.side) ? "BUY" : "SELL";
        String oppositeSide = "BUY".equals(binanceSide) ? "SELL" : "BUY";
        rec.setSide(binanceSide);

        String rationale = String.format("SM-Fib %s setup. Entry: %s, SL: %s, TP1: %s. R:R=%.2f",
                bestCandidate.result.deterministicEvidence().bias(),
                bestCandidate.plan.entryPrice(),
                bestCandidate.plan.slPrice(),
                bestCandidate.plan.tp1Price(),
                bestCandidate.plan.rrToTp1());

        rec.setRationaleText(rationale);
        rec.setConfidenceScore(bestCandidate.plan.rrToTp1());
        rec.setCreatedAt(Instant.now());
        rec.setStatus("NEW");
        rec.setDiagnosticsJson(writeJson(Map.of(
                "traceId", bestCandidate.result.traceId(),
                "integrityScore", bestCandidate.result.finalGate().finalIntegrityScore(),
                "recommendationEligible", bestCandidate.result.finalGate().eligible(),
                "aiReview", bestCandidate.result.aiReview(),
                "finalGate", bestCandidate.result.finalGate(),
                "snapshot", bestCandidate.result.snapshotSummary())));

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
        final String side;
        final String bias;
        final RiskAndSizingCalculator.ExecutionPlan plan;
        final DeepScanCandidateResult result;

        Candidate(String symbol, String side, String bias, DeepScanCandidateResult result) {
            this.symbol = symbol;
            this.side = side;
            this.bias = bias;
            this.result = result;
            this.plan = new RiskAndSizingCalculator.ExecutionPlan(
                    toBigDecimal(result.deterministicEvidence().metrics().get("entry")),
                    toBigDecimal(result.deterministicEvidence().metrics().get("sl")),
                    toBigDecimal(result.deterministicEvidence().metrics().get("tp1")),
                    toBigDecimal(result.deterministicEvidence().metrics().get("tp2")),
                    toBigDecimal(result.deterministicEvidence().metrics().get("tp3")),
                    toBigDecimal(result.deterministicEvidence().metrics().get("quantity")),
                    toBigDecimal(result.deterministicEvidence().metrics().get("rr_tp1")));
        }
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private String truncateSkipReason(String skipText) {
        if (skipText == null) {
            return null;
        }
        return skipText.length() > 255 ? skipText.substring(0, 255) : skipText;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("Failed to serialize scan payload: {}", ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> toAuditJson(StageAudit audit) {
        if (audit == null) {
            return Map.of();
        }
        return Map.of(
                "stage", audit.stage().name(),
                "status", audit.status(),
                "findings", audit.findings(),
                "details", audit.details(),
                "startedAt", audit.startedAt(),
                "finishedAt", audit.finishedAt());
    }
}
