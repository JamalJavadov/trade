package com.tradebot.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.DemoBinanceClient;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.dto.DemoTuningConfig;
import com.tradebot.demo.entity.DemoRun;
import com.tradebot.demo.entity.DemoTrade;
import com.tradebot.demo.entity.DemoStrategyConfigVersion;
import com.tradebot.demo.repository.DemoRunRepository;
import com.tradebot.demo.repository.DemoTradeRepository;
import com.tradebot.dto.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DemoOrchestrator {

    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final DemoRunRepository demoRunRepository;
    private final DemoTradeRepository demoTradeRepository;
    private final DemoAccountService demoAccountService;
    private final DemoStrategyConfigProvider demoStrategyConfigProvider;
    private final DemoTradeMonitor demoTradeMonitor;
    private final DemoMarketDataService demoMarketDataService;
    private final DemoCandidateEvaluator demoCandidateEvaluator;
    private final DemoRiskSizer demoRiskSizer;
    private final DemoMetricsService demoMetricsService;
    private final DemoBinanceClient binanceClient;
    private final ObjectMapper objectMapper;

    public CycleResult runCycle(String trigger) {
        DemoRun run = new DemoRun();
        run.setStartedAt(Instant.now());
        run.setStatus("STARTED");
        run = demoRunRepository.save(run);

        StringBuilder notes = new StringBuilder("trigger=").append(trigger == null ? "UNKNOWN" : trigger);
        UUID openedTradeId = null;

        try {
            var demoConfig = controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading();
            demoAccountService.getOrCreateAccount();
            demoMetricsService.updateTotalPnl(demoAccountService.netPnl());

            long openPositions = demoTradeRepository.countByStatus("OPEN");
            if (openPositions >= demoConfig.getMaxOpenPositions()) {
                demoTradeMonitor.tick();
                notes.append(" openPositionsAtCap=true");
                return finishRun(run, "FINISHED", notes.toString(), openedTradeId);
            }

            demoTradeMonitor.tick();

            openPositions = demoTradeRepository.countByStatus("OPEN");
            if (openPositions >= demoConfig.getMaxOpenPositions()) {
                notes.append(" openPositionsAtCapAfterMonitor=true");
                return finishRun(run, "FINISHED", notes.toString(), openedTradeId);
            }

            DemoStrategyConfigVersion activeVersion = demoStrategyConfigProvider.ensureActiveVersion();
            DemoTuningConfig config = demoStrategyConfigProvider.getActiveConfig();

            DemoMarketDataService.DemoUniverse universe = demoMarketDataService.buildTop300Universe();
            List<DemoCandidateEvaluator.CandidateResult> candidates = new ArrayList<>();

            for (DemoMarketDataService.DemoUniverseSymbol symbol : universe.symbols()) {
                try {
                    List<Candle> execCandles = binanceClient.getKlines(symbol.symbol(), "15m", 500);
                    List<Candle> biasCandles = binanceClient.getKlines(symbol.symbol(), "1h", 500);
                    DemoMarketDataService.SymbolFilters symbolFilters = universe.filtersBySymbol().get(symbol.symbol());
                    if (symbolFilters == null) {
                        symbolFilters = demoMarketDataService.getSymbolFilters(symbol.symbol());
                    }

                    DemoCandidateEvaluator.CandidateResult candidate = demoCandidateEvaluator.evaluateSymbol(
                            symbol.symbol(),
                            symbol.quoteVolume(),
                            execCandles,
                            biasCandles,
                            symbolFilters,
                            config);

                    if (candidate != null && candidate.rrToTp1().compareTo(new BigDecimal("2.0")) >= 0) {
                        candidates.add(candidate);
                    }
                } catch (Exception ex) {
                    log.debug("Skipping symbol {} due to evaluation error: {}", symbol.symbol(), ex.getMessage());
                }
            }

            if (candidates.isEmpty()) {
                notes.append(" reasonCode=NO_TRADE candidates=0");
                return finishRun(run, "FINISHED", notes.toString(), openedTradeId);
            }

            DemoCandidateEvaluator.CandidateResult bestCandidate = candidates.stream()
                    .max(Comparator.comparing(DemoCandidateEvaluator.CandidateResult::score))
                    .orElse(null);

            if (bestCandidate == null) {
                notes.append(" reasonCode=NO_TRADE bestCandidateMissing=true");
                return finishRun(run, "FINISHED", notes.toString(), openedTradeId);
            }

            notes.append(" bestCandidateSymbol=").append(bestCandidate.symbol())
                    .append(" bestCandidateSide=").append(bestCandidate.side());

            BigDecimal liveMark;
            try {
                liveMark = demoMarketDataService.getMarkPrice(bestCandidate.symbol());
            } catch (Exception ex) {
                return failRun(run, notes + " reasonCode=BINANCE_UNAVAILABLE phase=PRECHECK_MARK error=" + safe(ex.getMessage()), ex);
            }

            DemoMarketDataService.SymbolFilters filters = universe.filtersBySymbol().getOrDefault(
                    bestCandidate.symbol(),
                    demoMarketDataService.getSymbolFilters(bestCandidate.symbol()));

            DemoPreflightMath.PreflightResult preflight = DemoPreflightMath.evaluate(
                    bestCandidate.side(),
                    liveMark,
                    filters.tickSize(),
                    bestCandidate.tp1(),
                    bestCandidate.sl(),
                    DemoCandidateEvaluator.LOCKED_MIN_RR);

            if (!preflight.placeable()) {
                notes.append(" reasonCode=NO_PLACEABLE_TRADE")
                        .append(" preflightReason=").append(preflight.reasonCode())
                        .append(" rrLive=").append(preflight.rrLive());
                return finishRun(run, "FINISHED", notes.toString(), openedTradeId);
            }

            DemoRiskSizer.SizingResult sizing = demoRiskSizer.size(
                    demoAccountService.getOrCreateAccount().getEquityUsdt(),
                    demoConfig.getRiskPct(),
                    demoRiskSizer.applyEntrySlippage(bestCandidate.side(), liveMark,
                            BigDecimal.valueOf(demoConfig.getSlippageBps())),
                    bestCandidate.sl(),
                    filters.stepSize(),
                    filters.minQty(),
                    BigDecimal.valueOf(demoConfig.getFeeBps()));

            if (!sizing.placeable()) {
                notes.append(" reasonCode=").append(sizing.reasonCode());
                return finishRun(run, "FINISHED", notes.toString(), openedTradeId);
            }

            openedTradeId = openDemoTrade(bestCandidate, activeVersion, config, demoConfig, liveMark, filters, preflight, sizing);
            notes.append(" openedTradeId=").append(openedTradeId);

            return finishRun(run, "FINISHED", notes.toString(), openedTradeId);
        } catch (Exception ex) {
            if (isBinanceUnavailable(ex)) {
                return failRun(run, notes + " reasonCode=BINANCE_UNAVAILABLE error=" + safe(ex.getMessage()), ex);
            }
            return failRun(run, notes + " reasonCode=CYCLE_FAILED error=" + safe(ex.getMessage()), ex);
        }
    }

    private UUID openDemoTrade(
            DemoCandidateEvaluator.CandidateResult candidate,
            DemoStrategyConfigVersion activeVersion,
            DemoTuningConfig activeConfig,
            ControlCenterConfig.DemoTrading demoConfig,
            BigDecimal liveMark,
            DemoMarketDataService.SymbolFilters filters,
            DemoPreflightMath.PreflightResult preflight,
            DemoRiskSizer.SizingResult sizing) {

        Instant now = Instant.now();
        BigDecimal entryFill = demoRiskSizer.applyEntrySlippage(candidate.side(), liveMark,
                BigDecimal.valueOf(demoConfig.getSlippageBps()));

        DemoTrade trade = new DemoTrade();
        trade.setCreatedAt(now);
        trade.setOpenedAt(now);
        trade.setSymbol(candidate.symbol());
        trade.setSide(candidate.side());
        trade.setLeverage(demoConfig.getLeverageDefault());
        trade.setQty(sizing.qty());
        trade.setRemainingQty(sizing.qty());
        trade.setEntryPrice(entryFill);
        trade.setSlPrice(candidate.sl());
        trade.setCurrentSlPrice(candidate.sl());
        trade.setTp1Price(candidate.tp1());
        trade.setTp2Price(candidate.tp2());
        trade.setTp3Price(candidate.tp3());
        trade.setStatus("OPEN");
        trade.setWorkingType("MARK_PRICE");
        trade.setStage(0);
        trade.setLastMarkPrice(liveMark);
        trade.setRiskUsdtInitial(sizing.riskUsdt());
        trade.setEntryFeeUsdt(sizing.entryFee());
        trade.setExitFeeUsdt(BigDecimal.ZERO);
        trade.setTotalFeesUsdt(sizing.entryFee());
        trade.setRealizedPnlUsdt(sizing.entryFee().negate());

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("diagnostics", candidate.diagnosticsSnapshot());
        snapshot.put("liveMarkAtEntry", liveMark);
        snapshot.put("tickSize", filters.tickSize());
        snapshot.put("stepSize", filters.stepSize());
        snapshot.put("minQty", filters.minQty());
        snapshot.put("rrToTp1Setup", candidate.rrToTp1());
        snapshot.put("rrLive", preflight.rrLive());
        snapshot.put("riskUsdt", sizing.riskUsdt());
        snapshot.put("stopDistance", sizing.stopDistance());
        snapshot.put("configVersion", activeVersion != null ? activeVersion.getVersion() : "default");
        snapshot.put("management", Map.of(
                "partialTp1Pct", activeConfig.getManagement().getPartialTp1Pct(),
                "partialTp2Pct", activeConfig.getManagement().getPartialTp2Pct(),
                "partialTp3Pct", activeConfig.getPartialTp3Pct(),
                "timeStopMinutes", activeConfig.getManagement().getTimeStopMinutes()));

        try {
            trade.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
        } catch (Exception ex) {
            log.warn("Unable to serialize demo trade snapshot for {}: {}", candidate.symbol(), ex.getMessage());
            trade.setSnapshotJson("{}");
        }

        DemoTrade saved = demoTradeRepository.save(trade);
        demoMetricsService.incrementTradeOpened();
        return saved.getId();
    }

    private CycleResult finishRun(DemoRun run, String status, String notes, UUID openedTradeId) {
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        run.setNotes(notes);
        demoRunRepository.save(run);
        demoMetricsService.incrementCycle(status);
        return new CycleResult(run.getId(), status, notes, openedTradeId);
    }

    private CycleResult failRun(DemoRun run, String notes, Exception ex) {
        log.warn("Demo cycle failed: {}", ex.getMessage());
        return finishRun(run, "FAILED", notes, null);
    }

    private boolean isBinanceUnavailable(Exception ex) {
        return ex instanceof WebClientResponseException
                || ex instanceof WebClientRequestException
                || (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("binance"));
    }

    private String safe(String text) {
        if (text == null) {
            return "unknown";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > 240 ? compact.substring(0, 240) : compact;
    }

    public record CycleResult(UUID runId, String status, String notes, UUID openedTradeId) {
    }
}
