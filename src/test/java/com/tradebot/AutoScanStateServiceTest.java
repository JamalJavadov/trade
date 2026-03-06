package com.tradebot;

import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.AutoScanStateDTO;
import com.tradebot.entity.ScanRun;
import com.tradebot.repository.ScanRunRepository;
import com.tradebot.service.AutoScanStateService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoScanStateServiceTest {

    @Test
    void returnsRuntimeStateWithRunningAndLastRunSnapshots() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        ScanRunRepository scanRunRepository = mock(ScanRunRepository.class);
        AutoScanStateService service = new AutoScanStateService(settingsProvider, scanRunRepository);

        ControlCenterConfig config = new ControlCenterConfig();
        config.getScan().setAutoscanEnabled(true);
        config.getScan().setSafeMode(false);
        config.getScan().setIntervalMinutes(20);
        when(settingsProvider.getConfigSnapshot()).thenReturn(config);

        Instant now = Instant.now();
        ScanRun running = run("STARTED", "MANUAL", now.minusSeconds(30), null, null);
        ScanRun failed = run("FAILED", "SCHEDULED", now.minusSeconds(600), now.minusSeconds(540), "BINANCE_UPSTREAM");
        ScanRun latestScheduled = run("FINISHED", "SCHEDULED", now.minusSeconds(300), now.minusSeconds(240), null);

        when(scanRunRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of(running, failed, latestScheduled));
        when(scanRunRepository.findFirstByTriggerTypeOrderByStartedAtDesc("SCHEDULED"))
                .thenReturn(Optional.of(latestScheduled));

        AutoScanStateDTO dto = service.buildState();

        assertTrue(dto.isAutoscanEnabled());
        assertNotNull(dto.getRunningRun());
        assertEquals("STARTED", dto.getRunningRun().getStatus());
        assertNotNull(dto.getLastRun());
        assertEquals("FAILED", dto.getLastRun().getStatus());
        assertEquals("BINANCE_UPSTREAM", dto.getLastRun().getErrorCode());
        assertNotNull(dto.getNextRunAt());
        assertEquals(3, dto.getRecentRuns().size());
    }

    @Test
    void disablesNextRunWhenAutoscanDisabledOrSafeModeEnabled() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        ScanRunRepository scanRunRepository = mock(ScanRunRepository.class);
        AutoScanStateService service = new AutoScanStateService(settingsProvider, scanRunRepository);

        ControlCenterConfig config = new ControlCenterConfig();
        config.getScan().setAutoscanEnabled(false);
        config.getScan().setSafeMode(true);
        config.getScan().setIntervalMinutes(20);
        when(settingsProvider.getConfigSnapshot()).thenReturn(config);
        when(scanRunRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of());
        when(scanRunRepository.findFirstByTriggerTypeOrderByStartedAtDesc("SCHEDULED"))
                .thenReturn(Optional.empty());

        AutoScanStateDTO dto = service.buildState();

        assertFalse(dto.isAutoscanEnabled());
        assertNull(dto.getNextRunAt());
        assertTrue(dto.getRecentRuns().isEmpty());
    }

    private ScanRun run(String status, String triggerType, Instant startedAt, Instant finishedAt, String errorCode) {
        ScanRun run = new ScanRun();
        run.setId(UUID.randomUUID());
        run.setStatus(status);
        run.setTriggerType(triggerType);
        run.setRequestedAt(startedAt);
        run.setStartedAt(startedAt);
        run.setFinishedAt(finishedAt);
        run.setErrorCode(errorCode);
        run.setNotes(errorCode != null ? "failed" : null);
        run.setCorrelationId("trace-" + run.getId());
        run.setIntervalMinutes(20);
        run.setTopN(300);
        return run;
    }
}
