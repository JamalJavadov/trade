package com.tradebot.demo.service;

import com.tradebot.config.DemoTradingProperties;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Component
@RequiredArgsConstructor
@Slf4j
public class DemoTradingScheduler {

    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final DemoTradingProperties demoTradingProperties;
    private final DemoTradingRunService demoTradingRunService;
    private final DemoTradeMonitor demoTradeMonitor;
    private final DemoAccountService demoAccountService;

    private final ReentrantLock cycleLock = new ReentrantLock();
    private volatile Instant lastRunStartedAt;

    @PostConstruct
    public void init() {
        var demoConfig = controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading();
        if (demoConfig.isEnabled()) {
            assertDemoBinanceKeysConfigured();
            demoAccountService.setModeEnabled(true);
            log.info("Demo trading scheduler enabled from DB runtime state.");
        }
    }

    public boolean isRuntimeEnabled() {
        return controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading().isEnabled()
                && demoAccountService.isModeEnabled();
    }

    public boolean isCycleRunning() {
        return cycleLock.isLocked();
    }

    public DemoOrchestrator.CycleResult runOnceNow() {
        if (!isRuntimeEnabled()) {
            throw new IllegalStateException("Demo runtime is disabled. Toggle demoTrading.enabled in Control Center first.");
        }
        assertDemoBinanceKeysConfigured();
        if (!cycleLock.tryLock()) {
            throw new IllegalStateException("A demo cycle is already in progress.");
        }

        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", UUID.randomUUID().toString())) {
            lastRunStartedAt = Instant.now();
            return demoTradingRunService.executeOneCycle("MANUAL_RUN_ONCE");
        } finally {
            cycleLock.unlock();
        }
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void scheduledCycleTick() {
        if (!isRuntimeEnabled() || !isDue()) {
            return;
        }
        try {
            assertDemoBinanceKeysConfigured();
        } catch (IllegalStateException ex) {
            log.warn("Skipping scheduled demo cycle: {}", ex.getMessage());
            return;
        }
        if (!cycleLock.tryLock()) {
            log.debug("Skipping demo cycle because a previous cycle is still running.");
            return;
        }

        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", UUID.randomUUID().toString())) {
            lastRunStartedAt = Instant.now();
            demoTradingRunService.executeOneCycle("SCHEDULED");
        } catch (Exception ex) {
            log.warn("Scheduled demo cycle failed: {}", ex.getMessage());
        } finally {
            cycleLock.unlock();
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void scheduledMonitorTick() {
        if (!isRuntimeEnabled()) {
            return;
        }
        try {
            assertDemoBinanceKeysConfigured();
        } catch (IllegalStateException ex) {
            log.warn("Skipping scheduled demo monitor tick: {}", ex.getMessage());
            return;
        }
        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", UUID.randomUUID().toString())) {
            demoTradeMonitor.tick();
        } catch (Exception ex) {
            log.warn("Scheduled demo monitor tick failed: {}", ex.getMessage());
        }
    }

    private boolean isDue() {
        Instant last = lastRunStartedAt;
        if (last == null) {
            return true;
        }
        int intervalMinutes = Math.max(controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading().getIntervalMinutes(), 1);
        Instant dueAt = last.plus(intervalMinutes, ChronoUnit.MINUTES);
        return !dueAt.isAfter(Instant.now());
    }

    void assertDemoBinanceKeysConfigured() {
        String demoKey = demoTradingProperties.getBinance() != null ? demoTradingProperties.getBinance().getApiKey() : null;
        String demoSecret = demoTradingProperties.getBinance() != null
                ? demoTradingProperties.getBinance().getApiSecret()
                : null;

        if (demoKey == null || demoKey.isBlank() || demoSecret == null || demoSecret.isBlank()) {
            throw new IllegalStateException(
                    "Demo trading requires DEMOBINANCE_API_KEY and DEMOBINANCE_API_SECRET to be configured.");
        }
    }
}
