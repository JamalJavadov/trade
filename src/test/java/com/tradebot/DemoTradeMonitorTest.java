package com.tradebot;

import com.tradebot.config.DemoTradingProperties;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.entity.DemoAccount;
import com.tradebot.demo.entity.DemoTrade;
import com.tradebot.demo.dto.DemoTuningConfig;
import com.tradebot.demo.repository.DemoAccountEquityEventRepository;
import com.tradebot.demo.repository.DemoTradeRepository;
import com.tradebot.demo.service.DemoAccountService;
import com.tradebot.demo.service.DemoMarketDataService;
import com.tradebot.demo.service.DemoMetricsService;
import com.tradebot.demo.service.DemoRiskSizer;
import com.tradebot.demo.service.DemoStrategyConfigProvider;
import com.tradebot.demo.service.DemoTradeMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoTradeMonitorTest {

    @Test
    void longTradeClosesOnStopLoss() {
        DemoTradeRepository repo = mock(DemoTradeRepository.class);
        DemoMarketDataService market = mock(DemoMarketDataService.class);
        DemoAccountService accountService = mock(DemoAccountService.class);
        DemoMetricsService metrics = mock(DemoMetricsService.class);
        DemoTradingProperties props = baseProps();

        DemoTrade trade = baseOpenTrade("LONG");
        trade.setSlPrice(new BigDecimal("95"));
        trade.setCurrentSlPrice(new BigDecimal("95"));

        when(repo.findByStatusOrderByOpenedAtAsc("OPEN")).thenReturn(List.of(trade));
        when(repo.save(any(DemoTrade.class))).thenAnswer(inv -> inv.getArgument(0));
        when(market.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("94"));
        when(market.getSymbolFilters("BTCUSDT"))
                .thenReturn(new DemoMarketDataService.SymbolFilters(new BigDecimal("0.1"), new BigDecimal("0.1"),
                        new BigDecimal("0.1")));
        when(accountService.applyRealizedPnl(any())).thenReturn(new DemoAccount());
        when(accountService.netPnl()).thenReturn(new BigDecimal("-1"));

        DemoTradeMonitor monitor = createMonitor(repo, market, props, accountService, metrics);
        monitor.tick();

        ArgumentCaptor<DemoTrade> savedCaptor = ArgumentCaptor.forClass(DemoTrade.class);
        verify(repo).save(savedCaptor.capture());
        DemoTrade saved = savedCaptor.getValue();

        assertEquals("CLOSED", saved.getStatus());
        assertEquals("SL", saved.getCloseReason());
        assertEquals(BigDecimal.ZERO, saved.getRemainingQty());
        assertTrue(saved.getPnlUsdt().compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void shortTradeHitsTp1AndMovesToStageOne() {
        DemoTradeRepository repo = mock(DemoTradeRepository.class);
        DemoMarketDataService market = mock(DemoMarketDataService.class);
        DemoAccountService accountService = mock(DemoAccountService.class);
        DemoMetricsService metrics = mock(DemoMetricsService.class);
        DemoTradingProperties props = baseProps();

        DemoTrade trade = baseOpenTrade("SHORT");
        trade.setSlPrice(new BigDecimal("105"));
        trade.setCurrentSlPrice(new BigDecimal("105"));
        trade.setTp1Price(new BigDecimal("95"));
        trade.setTp2Price(new BigDecimal("93"));
        trade.setTp3Price(new BigDecimal("90"));

        when(repo.findByStatusOrderByOpenedAtAsc("OPEN")).thenReturn(List.of(trade));
        when(repo.save(any(DemoTrade.class))).thenAnswer(inv -> inv.getArgument(0));
        when(market.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("94"));
        when(market.getSymbolFilters("BTCUSDT"))
                .thenReturn(new DemoMarketDataService.SymbolFilters(new BigDecimal("0.1"), new BigDecimal("0.1"),
                        new BigDecimal("0.1")));

        DemoTradeMonitor monitor = createMonitor(repo, market, props, accountService, metrics);
        monitor.tick();

        ArgumentCaptor<DemoTrade> savedCaptor = ArgumentCaptor.forClass(DemoTrade.class);
        verify(repo).save(savedCaptor.capture());
        DemoTrade saved = savedCaptor.getValue();

        assertEquals("OPEN", saved.getStatus());
        assertEquals(1, saved.getStage());
        assertEquals(new BigDecimal("0.5"), saved.getRemainingQty());
        assertEquals(saved.getEntryPrice(), saved.getCurrentSlPrice());
    }

    @Test
    void timeStopClosesTradeAtMark() {
        DemoTradeRepository repo = mock(DemoTradeRepository.class);
        DemoMarketDataService market = mock(DemoMarketDataService.class);
        DemoAccountService accountService = mock(DemoAccountService.class);
        DemoMetricsService metrics = mock(DemoMetricsService.class);
        DemoTradingProperties props = baseProps();
        props.setTimeStopMinutes(90);

        DemoTrade trade = baseOpenTrade("LONG");
        trade.setOpenedAt(Instant.now().minusSeconds(100 * 60L));

        when(repo.findByStatusOrderByOpenedAtAsc("OPEN")).thenReturn(List.of(trade));
        when(repo.save(any(DemoTrade.class))).thenAnswer(inv -> inv.getArgument(0));
        when(market.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("100"));
        when(market.getSymbolFilters("BTCUSDT"))
                .thenReturn(new DemoMarketDataService.SymbolFilters(new BigDecimal("0.1"), new BigDecimal("0.1"),
                        new BigDecimal("0.1")));
        when(accountService.applyRealizedPnl(any())).thenReturn(new DemoAccount());
        when(accountService.netPnl()).thenReturn(BigDecimal.ZERO);

        DemoTradeMonitor monitor = createMonitor(repo, market, props, accountService, metrics);
        monitor.tick();

        ArgumentCaptor<DemoTrade> savedCaptor = ArgumentCaptor.forClass(DemoTrade.class);
        verify(repo).save(savedCaptor.capture());
        DemoTrade saved = savedCaptor.getValue();

        assertEquals("CLOSED", saved.getStatus());
        assertEquals("TIME_STOP", saved.getCloseReason());
    }

    @Test
    void manualCancelClosesTrade() {
        DemoTradeRepository repo = mock(DemoTradeRepository.class);
        DemoMarketDataService market = mock(DemoMarketDataService.class);
        DemoAccountService accountService = mock(DemoAccountService.class);
        DemoMetricsService metrics = mock(DemoMetricsService.class);
        DemoTradingProperties props = baseProps();

        DemoTrade trade = baseOpenTrade("LONG");
        UUID id = UUID.randomUUID();
        trade.setId(id);

        when(repo.findById(id)).thenReturn(java.util.Optional.of(trade));
        when(repo.save(any(DemoTrade.class))).thenAnswer(inv -> inv.getArgument(0));
        when(market.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("100"));
        when(market.getSymbolFilters("BTCUSDT"))
                .thenReturn(new DemoMarketDataService.SymbolFilters(new BigDecimal("0.1"), new BigDecimal("0.1"),
                        new BigDecimal("0.1")));
        when(accountService.applyRealizedPnl(any())).thenReturn(new DemoAccount());
        when(accountService.netPnl()).thenReturn(BigDecimal.ZERO);

        DemoTradeMonitor monitor = createMonitor(repo, market, props, accountService, metrics);
        DemoTrade saved = monitor.cancelTrade(id);

        assertEquals("CLOSED", saved.getStatus());
        assertEquals("MANUAL_CANCEL", saved.getCloseReason());
    }

    private DemoTradingProperties baseProps() {
        DemoTradingProperties props = new DemoTradingProperties();
        props.setSlippageBps(2);
        props.setFeeBps(4);
        props.setTimeStopMinutes(90);
        return props;
    }

    private DemoTrade baseOpenTrade(String side) {
        DemoTrade trade = new DemoTrade();
        trade.setId(UUID.randomUUID());
        trade.setCreatedAt(Instant.now());
        trade.setOpenedAt(Instant.now().minusSeconds(30));
        trade.setSymbol("BTCUSDT");
        trade.setSide(side);
        trade.setLeverage(5);
        trade.setQty(new BigDecimal("1.0"));
        trade.setRemainingQty(new BigDecimal("1.0"));
        trade.setEntryPrice(new BigDecimal("100"));
        trade.setSlPrice(new BigDecimal("95"));
        trade.setCurrentSlPrice(new BigDecimal("95"));
        trade.setTp1Price(new BigDecimal("105"));
        trade.setTp2Price(new BigDecimal("108"));
        trade.setTp3Price(new BigDecimal("110"));
        trade.setWorkingType("MARK_PRICE");
        trade.setStatus("OPEN");
        trade.setStage(0);
        trade.setRiskUsdtInitial(new BigDecimal("5"));
        trade.setEntryFeeUsdt(BigDecimal.ZERO);
        trade.setExitFeeUsdt(BigDecimal.ZERO);
        trade.setTotalFeesUsdt(BigDecimal.ZERO);
        trade.setRealizedPnlUsdt(BigDecimal.ZERO);
        return trade;
    }

    private DemoTradeMonitor createMonitor(
            DemoTradeRepository repo,
            DemoMarketDataService market,
            DemoTradingProperties props,
            DemoAccountService accountService,
            DemoMetricsService metrics) {
        DemoAccountEquityEventRepository equityEventRepository = mock(DemoAccountEquityEventRepository.class);
        DemoStrategyConfigProvider configProvider = mock(DemoStrategyConfigProvider.class);
        ControlCenterSettingsProvider controlCenterSettingsProvider = mock(ControlCenterSettingsProvider.class);
        ControlCenterConfig controlConfig = new ControlCenterConfig();
        controlConfig.getDemoTrading().setFeeBps(props.getFeeBps());
        controlConfig.getDemoTrading().setSlippageBps(props.getSlippageBps());
        controlConfig.getDemoTrading().setTimeStopMinutes(props.getTimeStopMinutes());
        when(controlCenterSettingsProvider.getConfigSnapshot()).thenReturn(controlConfig);
        DemoTuningConfig tuning = new DemoTuningConfig();
        tuning.getManagement().setTimeStopMinutes(props.getTimeStopMinutes());
        when(configProvider.getActiveConfig()).thenReturn(tuning);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        return new DemoTradeMonitor(
                repo,
                market,
                controlCenterSettingsProvider,
                new DemoRiskSizer(),
                accountService,
                metrics,
                equityEventRepository,
                configProvider,
                new ObjectMapper(),
                eventPublisher);
    }
}
