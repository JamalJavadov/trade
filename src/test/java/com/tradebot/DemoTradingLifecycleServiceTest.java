package com.tradebot;

import com.tradebot.config.DemoTradingProperties;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.dto.DemoActionResponseDTO;
import com.tradebot.demo.repository.DemoRunRepository;
import com.tradebot.demo.repository.DemoTradeRepository;
import com.tradebot.demo.service.DemoAccountService;
import com.tradebot.demo.service.DemoOrchestrator;
import com.tradebot.demo.service.DemoStrategyConfigService;
import com.tradebot.demo.service.DemoTradingLifecycleService;
import com.tradebot.demo.service.DemoTradingQueryService;
import com.tradebot.demo.service.DemoTradingScheduler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoTradingLifecycleServiceTest {

    private ControlCenterSettingsProvider provider(boolean enabled, boolean useMarkPrice) {
        ControlCenterSettingsProvider provider = mock(ControlCenterSettingsProvider.class);
        ControlCenterConfig config = new ControlCenterConfig();
        config.getDemoTrading().setEnabled(enabled);
        config.getDemoTrading().setUseMarkPrice(useMarkPrice);
        when(provider.getConfigSnapshot()).thenReturn(config);
        return provider;
    }

    @Test
    void enableBlockedWhenFeatureFlagDisabled() {
        DemoTradingProperties properties = new DemoTradingProperties();

        DemoTradingLifecycleService service = new DemoTradingLifecycleService(
                properties,
                provider(false, true),
                mock(DemoTradingScheduler.class),
                mock(DemoStrategyConfigService.class),
                mock(DemoTradingQueryService.class),
                mock(DemoAccountService.class),
                mock(DemoTradeRepository.class),
                mock(DemoRunRepository.class),
                mock(EntityManager.class));

        assertThrows(IllegalStateException.class, service::enable);
    }

    @Test
    void enableSetsModeEnabledAndSeedsConfig() {
        DemoTradingProperties properties = new DemoTradingProperties();
        setDemoKeys(properties);

        DemoAccountService accountService = mock(DemoAccountService.class);
        DemoStrategyConfigService configService = mock(DemoStrategyConfigService.class);

        DemoTradingLifecycleService service = new DemoTradingLifecycleService(
                properties,
                provider(true, true),
                mock(DemoTradingScheduler.class),
                configService,
                mock(DemoTradingQueryService.class),
                accountService,
                mock(DemoTradeRepository.class),
                mock(DemoRunRepository.class),
                mock(EntityManager.class));

        DemoActionResponseDTO response = service.enable();

        assertEquals(true, response.isRunning());
        verify(accountService).setModeEnabled(true);
        verify(configService).ensureActiveVersion();
    }

    @Test
    void runOnceDelegatesToScheduler() {
        DemoTradingProperties properties = new DemoTradingProperties();
        setDemoKeys(properties);

        DemoTradingScheduler scheduler = mock(DemoTradingScheduler.class);
        when(scheduler.runOnceNow()).thenReturn(new DemoOrchestrator.CycleResult(
                UUID.randomUUID(), "FINISHED", "ok", null));
        when(scheduler.isRuntimeEnabled()).thenReturn(true);

        DemoTradingLifecycleService service = new DemoTradingLifecycleService(
                properties,
                provider(true, true),
                scheduler,
                mock(DemoStrategyConfigService.class),
                mock(DemoTradingQueryService.class),
                mock(DemoAccountService.class),
                mock(DemoTradeRepository.class),
                mock(DemoRunRepository.class),
                mock(EntityManager.class));

        DemoActionResponseDTO response = service.runOnce();

        assertEquals(true, response.isRunning());
        verify(scheduler).runOnceNow();
    }

    @Test
    void resetPurgesAllDemoTablesAndDisablesMode() {
        DemoTradingProperties properties = new DemoTradingProperties();

        DemoAccountService accountService = mock(DemoAccountService.class);
        DemoStrategyConfigService configService = mock(DemoStrategyConfigService.class);
        DemoTradeRepository tradeRepository = mock(DemoTradeRepository.class);
        DemoRunRepository runRepository = mock(DemoRunRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        Query truncateQuery = mock(Query.class);

        when(entityManager.createNativeQuery(any())).thenReturn(truncateQuery);
        when(truncateQuery.executeUpdate()).thenReturn(0);

        DemoTradingLifecycleService service = new DemoTradingLifecycleService(
                properties,
                provider(true, true),
                mock(DemoTradingScheduler.class),
                configService,
                mock(DemoTradingQueryService.class),
                accountService,
                tradeRepository,
                runRepository,
                entityManager);

        DemoActionResponseDTO response = service.reset(true);

        assertFalse(response.isRunning());
        verify(accountService).setModeEnabled(false);
        verify(entityManager).createNativeQuery(any());
        verify(truncateQuery).executeUpdate();
        verify(configService).ensureActiveVersion();
    }

    @Test
    void enableBlockedWhenDemoKeysMissing() {
        DemoTradingProperties properties = new DemoTradingProperties();

        DemoTradingLifecycleService service = new DemoTradingLifecycleService(
                properties,
                provider(true, true),
                mock(DemoTradingScheduler.class),
                mock(DemoStrategyConfigService.class),
                mock(DemoTradingQueryService.class),
                mock(DemoAccountService.class),
                mock(DemoTradeRepository.class),
                mock(DemoRunRepository.class),
                mock(EntityManager.class));

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::enable);
        assertEquals("Demo trading requires DEMOBINANCE_API_KEY and DEMOBINANCE_API_SECRET to be configured.",
                ex.getMessage());
    }

    @Test
    void runOnceBlockedWhenDemoKeysMissing() {
        DemoTradingProperties properties = new DemoTradingProperties();

        DemoTradingLifecycleService service = new DemoTradingLifecycleService(
                properties,
                provider(true, true),
                mock(DemoTradingScheduler.class),
                mock(DemoStrategyConfigService.class),
                mock(DemoTradingQueryService.class),
                mock(DemoAccountService.class),
                mock(DemoTradeRepository.class),
                mock(DemoRunRepository.class),
                mock(EntityManager.class));

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::runOnce);
        assertEquals("Demo trading requires DEMOBINANCE_API_KEY and DEMOBINANCE_API_SECRET to be configured.",
                ex.getMessage());
    }

    private void setDemoKeys(DemoTradingProperties properties) {
        DemoTradingProperties.BinanceCredentials demoBinance = new DemoTradingProperties.BinanceCredentials();
        demoBinance.setApiKey("demo-key");
        demoBinance.setApiSecret("demo-secret");
        properties.setBinance(demoBinance);
    }
}
