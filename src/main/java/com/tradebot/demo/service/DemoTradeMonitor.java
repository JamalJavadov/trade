package com.tradebot.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.entity.DemoAccount;
import com.tradebot.demo.entity.DemoAccountEquityEvent;
import com.tradebot.demo.entity.DemoTrade;
import com.tradebot.demo.repository.DemoAccountEquityEventRepository;
import com.tradebot.demo.repository.DemoTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class DemoTradeMonitor {

    private final DemoTradeRepository demoTradeRepository;
    private final DemoMarketDataService demoMarketDataService;
    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final DemoRiskSizer demoRiskSizer;
    private final DemoAccountService demoAccountService;
    private final DemoMetricsService demoMetricsService;
    private final DemoAccountEquityEventRepository equityEventRepository;
    private final DemoStrategyConfigProvider configProvider;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    private final ReentrantLock monitorLock = new ReentrantLock();

    public void tick() {
        if (!monitorLock.tryLock()) {
            log.debug("Demo monitor tick skipped because previous tick is still running");
            return;
        }
        try {
            doTick();
        } finally {
            monitorLock.unlock();
        }
    }

    @Transactional
    public DemoTrade cancelTrade(UUID tradeId) {
        DemoTrade trade = demoTradeRepository.findById(tradeId)
                .orElseThrow(() -> new IllegalArgumentException("Demo trade not found: " + tradeId));
        if (!"OPEN".equals(trade.getStatus())) {
            throw new IllegalStateException("Only OPEN demo trades can be cancelled.");
        }

        if (!monitorLock.tryLock()) {
            throw new IllegalStateException("Demo monitor is busy, retry manual cancel.");
        }
        try {
            BigDecimal mark = demoMarketDataService.getMarkPrice(trade.getSymbol());
            DemoMarketDataService.SymbolFilters filters = demoMarketDataService.getSymbolFilters(trade.getSymbol());
            closeRemaining(trade, mark, filters.stepSize(), "MANUAL_CANCEL");
            DemoTrade saved = demoTradeRepository.save(trade);
            publishClosed(saved);
            return saved;
        } finally {
            monitorLock.unlock();
        }
    }

    @Transactional
    protected void doTick() {
        List<DemoTrade> openTrades = demoTradeRepository.findByStatusOrderByOpenedAtAsc("OPEN");
        for (DemoTrade trade : openTrades) {
            try {
                processTrade(trade);
            } catch (Exception ex) {
                log.warn("Demo monitor failed for trade {} symbol {}: {}",
                        trade.getId(), trade.getSymbol(), ex.getMessage());
            }
        }
    }

    private void processTrade(DemoTrade trade) {
        BigDecimal mark = demoMarketDataService.getMarkPrice(trade.getSymbol());
        DemoMarketDataService.SymbolFilters filters = demoMarketDataService.getSymbolFilters(trade.getSymbol());
        ManagementValues management = loadManagement(trade);

        normalizeOpenState(trade);
        trade.setLastMarkPrice(mark);

        if (isTimeStopReached(trade, management.timeStopMinutes())) {
            closeRemaining(trade, mark, filters.stepSize(), "TIME_STOP");
            DemoTrade saved = demoTradeRepository.save(trade);
            publishClosed(saved);
            return;
        }

        if (isStopHit(trade, mark)) {
            closeRemaining(trade, mark, filters.stepSize(), "SL");
            DemoTrade saved = demoTradeRepository.save(trade);
            publishClosed(saved);
            return;
        }

        if (isTpHit(trade, mark, trade.getTp3Price())) {
            closeRemaining(trade, mark, filters.stepSize(), "TP3");
            DemoTrade saved = demoTradeRepository.save(trade);
            publishClosed(saved);
            return;
        }

        int stage = trade.getStage() == null ? 0 : trade.getStage();
        if (stage == 0 && isTpHit(trade, mark, trade.getTp1Price())) {
            BigDecimal qtyToClose = demoRiskSizer.roundDownToStep(
                    trade.getQty().multiply(management.partialTp1Pct()), filters.stepSize());
            if (qtyToClose.compareTo(BigDecimal.ZERO) <= 0 || qtyToClose.compareTo(trade.getRemainingQty()) >= 0) {
                closeRemaining(trade, mark, filters.stepSize(), "TP1");
                DemoTrade saved = demoTradeRepository.save(trade);
                publishClosed(saved);
            } else {
                applyPartialClose(trade, qtyToClose, mark);
                trade.setStage(1);
                trade.setCurrentSlPrice(trade.getEntryPrice());
                demoTradeRepository.save(trade);
            }
            return;
        }

        if (stage == 1 && trade.getTp2Price() != null && isTpHit(trade, mark, trade.getTp2Price())) {
            BigDecimal qtyToClose = demoRiskSizer.roundDownToStep(
                    trade.getQty().multiply(management.partialTp2Pct()), filters.stepSize());
            if (qtyToClose.compareTo(BigDecimal.ZERO) <= 0 || qtyToClose.compareTo(trade.getRemainingQty()) >= 0) {
                closeRemaining(trade, mark, filters.stepSize(), "TP2");
                DemoTrade saved = demoTradeRepository.save(trade);
                publishClosed(saved);
            } else {
                applyPartialClose(trade, qtyToClose, mark);
                trade.setStage(2);
                trade.setCurrentSlPrice(trade.getTp1Price());
                demoTradeRepository.save(trade);
            }
            return;
        }

        demoTradeRepository.save(trade);
    }

    private void closeRemaining(DemoTrade trade, BigDecimal mark, BigDecimal stepSize, String reason) {
        normalizeOpenState(trade);
        BigDecimal qtyToClose = demoRiskSizer.roundDownToStep(trade.getRemainingQty(), stepSize);
        if (qtyToClose.compareTo(BigDecimal.ZERO) <= 0) {
            qtyToClose = trade.getRemainingQty();
        }
        if (qtyToClose.compareTo(BigDecimal.ZERO) <= 0) {
            qtyToClose = trade.getQty();
        }

        applyPartialClose(trade, qtyToClose, mark);

        trade.setStatus("CLOSED");
        trade.setCloseReason(reason);
        trade.setClosedAt(Instant.now());
        trade.setRemainingQty(BigDecimal.ZERO);
        trade.setPnlUsdt(trade.getRealizedPnlUsdt());

        if (trade.getRiskUsdtInitial() != null && trade.getRiskUsdtInitial().compareTo(BigDecimal.ZERO) > 0) {
            trade.setRMultiple(trade.getRealizedPnlUsdt()
                    .divide(trade.getRiskUsdtInitial(), 6, RoundingMode.HALF_UP));
        } else {
            trade.setRMultiple(BigDecimal.ZERO);
        }

        DemoAccount account = demoAccountService.applyRealizedPnl(trade.getRealizedPnlUsdt());
        persistEquityEvent(trade, account, reason);

        demoMetricsService.incrementTradeClosed(reason);
        demoMetricsService.updateTotalPnl(demoAccountService.netPnl());
    }

    private void persistEquityEvent(DemoTrade trade, DemoAccount account, String reason) {
        DemoAccountEquityEvent event = new DemoAccountEquityEvent();
        event.setCreatedAt(Instant.now());
        event.setTradeId(trade.getId());
        event.setCloseReason(reason);
        event.setBalanceUsdt(account.getBalanceUsdt());
        event.setEquityUsdt(account.getEquityUsdt());
        event.setRealizedPnlUsdt(trade.getRealizedPnlUsdt() == null ? BigDecimal.ZERO : trade.getRealizedPnlUsdt());
        event.setEventType("TRADE_CLOSED");
        equityEventRepository.save(event);
    }

    private void publishClosed(DemoTrade trade) {
        if (trade != null && "CLOSED".equals(trade.getStatus()) && trade.getId() != null) {
            eventPublisher.publishEvent(new DemoTradeClosedEvent(trade.getId()));
        }
    }

    private void applyPartialClose(DemoTrade trade, BigDecimal qtyToClose, BigDecimal mark) {
        var demoConfig = controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading();
        BigDecimal exitFill = demoRiskSizer.applyExitSlippage(trade.getSide(), mark,
                BigDecimal.valueOf(demoConfig.getSlippageBps()));
        BigDecimal exitFee = qtyToClose
                .multiply(exitFill)
                .multiply(BigDecimal.valueOf(demoConfig.getFeeBps()))
                .divide(new BigDecimal("10000"), 8, RoundingMode.HALF_UP);

        BigDecimal grossPnl;
        if (DemoPreflightMath.isLong(trade.getSide())) {
            grossPnl = qtyToClose.multiply(exitFill.subtract(trade.getEntryPrice()));
        } else {
            grossPnl = qtyToClose.multiply(trade.getEntryPrice().subtract(exitFill));
        }

        BigDecimal netPnl = grossPnl.subtract(exitFee);

        trade.setLastMarkPrice(mark);
        trade.setRemainingQty(trade.getRemainingQty().subtract(qtyToClose).max(BigDecimal.ZERO));
        trade.setRealizedPnlUsdt(trade.getRealizedPnlUsdt().add(netPnl));
        trade.setExitFeeUsdt(trade.getExitFeeUsdt().add(exitFee));
        trade.setTotalFeesUsdt(trade.getEntryFeeUsdt().add(trade.getExitFeeUsdt()));
    }

    private boolean isStopHit(DemoTrade trade, BigDecimal mark) {
        BigDecimal currentSl = trade.getCurrentSlPrice() == null ? trade.getSlPrice() : trade.getCurrentSlPrice();
        if (DemoPreflightMath.isLong(trade.getSide())) {
            return mark.compareTo(currentSl) <= 0;
        }
        return mark.compareTo(currentSl) >= 0;
    }

    private boolean isTpHit(DemoTrade trade, BigDecimal mark, BigDecimal target) {
        if (target == null) {
            return false;
        }
        if (DemoPreflightMath.isLong(trade.getSide())) {
            return mark.compareTo(target) >= 0;
        }
        return mark.compareTo(target) <= 0;
    }

    private boolean isTimeStopReached(DemoTrade trade, int timeStopMinutes) {
        if (trade.getOpenedAt() == null) {
            return false;
        }
        Duration elapsed = Duration.between(trade.getOpenedAt(), Instant.now());
        return elapsed.toMinutes() >= timeStopMinutes;
    }

    private void normalizeOpenState(DemoTrade trade) {
        if (trade.getRemainingQty() == null || trade.getRemainingQty().compareTo(BigDecimal.ZERO) <= 0) {
            trade.setRemainingQty(trade.getQty());
        }
        if (trade.getStage() == null) {
            trade.setStage(0);
        }
        if (trade.getCurrentSlPrice() == null) {
            trade.setCurrentSlPrice(trade.getSlPrice());
        }
        if (trade.getRealizedPnlUsdt() == null) {
            trade.setRealizedPnlUsdt(BigDecimal.ZERO);
        }
        if (trade.getEntryFeeUsdt() == null) {
            trade.setEntryFeeUsdt(BigDecimal.ZERO);
        }
        if (trade.getExitFeeUsdt() == null) {
            trade.setExitFeeUsdt(BigDecimal.ZERO);
        }
        if (trade.getTotalFeesUsdt() == null) {
            trade.setTotalFeesUsdt(trade.getEntryFeeUsdt().add(trade.getExitFeeUsdt()));
        }
    }

    private ManagementValues loadManagement(DemoTrade trade) {
        var active = configProvider.getActiveConfig();
        int runtimeTimeStop = Math.max(controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading().getTimeStopMinutes(), 1);
        BigDecimal defaultTp1 = active.getManagement().getPartialTp1Pct();
        BigDecimal defaultTp2 = active.getManagement().getPartialTp2Pct();
        int defaultTimeStop = runtimeTimeStop;

        if (trade.getSnapshotJson() == null || trade.getSnapshotJson().isBlank()) {
            return new ManagementValues(defaultTp1, defaultTp2, defaultTimeStop);
        }

        try {
            JsonNode root = objectMapper.readTree(trade.getSnapshotJson());
            JsonNode management = root.path("management");

            BigDecimal tp1 = decimalOrDefault(management.path("partialTp1Pct"), defaultTp1);
            BigDecimal tp2 = decimalOrDefault(management.path("partialTp2Pct"), defaultTp2);
            int timeStop = management.path("timeStopMinutes").isInt()
                    ? management.path("timeStopMinutes").intValue()
                    : defaultTimeStop;

            if (timeStop < 1) {
                timeStop = defaultTimeStop;
            }
            return new ManagementValues(tp1, tp2, timeStop);
        } catch (Exception e) {
            return new ManagementValues(defaultTp1, defaultTp2, defaultTimeStop);
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

    private record ManagementValues(BigDecimal partialTp1Pct, BigDecimal partialTp2Pct, int timeStopMinutes) {
    }
}
