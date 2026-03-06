package com.tradebot;

import com.tradebot.config.DemoTradingProperties;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.service.DemoAccountService;
import com.tradebot.demo.service.DemoTradeMonitor;
import com.tradebot.demo.service.DemoTradingRunService;
import com.tradebot.demo.service.DemoTradingScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoTradingSchedulerTest {

    private ControlCenterSettingsProvider providerWithDemo(boolean enabled, boolean autostart, int intervalMinutes) {
        ControlCenterSettingsProvider provider = mock(ControlCenterSettingsProvider.class);
        ControlCenterConfig config = new ControlCenterConfig();
        config.getDemoTrading().setEnabled(enabled);
        config.getDemoTrading().setAutostart(autostart);
        config.getDemoTrading().setIntervalMinutes(intervalMinutes);
        when(provider.getConfigSnapshot()).thenReturn(config);
        return provider;
    }

    @Test
    void initFailsFastWhenAutostartEnabledAndDemoKeysMissing() {
        DemoTradingProperties properties = new DemoTradingProperties();

        DemoAccountService accountService = mock(DemoAccountService.class);

        DemoTradingScheduler scheduler = new DemoTradingScheduler(
                providerWithDemo(true, true, 15),
                properties,
                mock(DemoTradingRunService.class),
                mock(DemoTradeMonitor.class),
                accountService);

        IllegalStateException ex = assertThrows(IllegalStateException.class, scheduler::init);
        assertEquals("Demo trading requires DEMOBINANCE_API_KEY and DEMOBINANCE_API_SECRET to be configured.",
                ex.getMessage());
    }

    @Test
    void runOnceNowFailsWhenDemoKeysMissing() {
        DemoTradingProperties properties = new DemoTradingProperties();

        DemoAccountService accountService = mock(DemoAccountService.class);
        when(accountService.isModeEnabled()).thenReturn(true);

        DemoTradingScheduler scheduler = new DemoTradingScheduler(
                providerWithDemo(true, false, 15),
                properties,
                mock(DemoTradingRunService.class),
                mock(DemoTradeMonitor.class),
                accountService);

        IllegalStateException ex = assertThrows(IllegalStateException.class, scheduler::runOnceNow);
        assertEquals("Demo trading requires DEMOBINANCE_API_KEY and DEMOBINANCE_API_SECRET to be configured.",
                ex.getMessage());
    }

    @Test
    void scheduledCycleTickSkipsWhenDemoKeysMissing() {
        DemoTradingProperties properties = new DemoTradingProperties();

        DemoTradingRunService runService = mock(DemoTradingRunService.class);
        DemoTradeMonitor tradeMonitor = mock(DemoTradeMonitor.class);
        DemoAccountService accountService = mock(DemoAccountService.class);
        when(accountService.isModeEnabled()).thenReturn(true);

        DemoTradingScheduler scheduler = new DemoTradingScheduler(
                providerWithDemo(true, false, 15),
                properties,
                runService,
                tradeMonitor,
                accountService);

        scheduler.scheduledCycleTick();

        verify(runService, never()).executeOneCycle(org.mockito.ArgumentMatchers.anyString());
        verify(tradeMonitor, never()).tick();
    }

    @Test
    void scheduledMonitorTickSkipsWhenDemoKeysMissing() {
        DemoTradingProperties properties = new DemoTradingProperties();

        DemoTradingRunService runService = mock(DemoTradingRunService.class);
        DemoTradeMonitor tradeMonitor = mock(DemoTradeMonitor.class);
        DemoAccountService accountService = mock(DemoAccountService.class);
        when(accountService.isModeEnabled()).thenReturn(true);

        DemoTradingScheduler scheduler = new DemoTradingScheduler(
                providerWithDemo(true, false, 15),
                properties,
                runService,
                tradeMonitor,
                accountService);

        scheduler.scheduledMonitorTick();

        verify(tradeMonitor, never()).tick();
        verify(runService, never()).executeOneCycle(org.mockito.ArgumentMatchers.anyString());
    }
}
