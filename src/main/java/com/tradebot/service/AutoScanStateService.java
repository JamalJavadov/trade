package com.tradebot.service;

import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.AutoScanStateDTO;
import com.tradebot.entity.ScanRun;
import com.tradebot.repository.ScanRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AutoScanStateService {

    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final ScanRunRepository scanRunRepository;

    public AutoScanStateDTO buildState() {
        ControlCenterConfig.Scan scanSettings = controlCenterSettingsProvider.getConfigSnapshot().getScan();
        Instant now = Instant.now();

        AutoScanStateDTO dto = new AutoScanStateDTO();
        dto.setAutoscanEnabled(scanSettings.isAutoscanEnabled());
        dto.setSafeMode(scanSettings.isSafeMode());
        dto.setIntervalMinutes(Math.max(scanSettings.getIntervalMinutes(), 1));
        dto.setServerTime(now);

        List<ScanRun> recent = scanRunRepository.findTop10ByOrderByStartedAtDesc();
        dto.setRecentRuns(recent.stream().map(this::toSnapshot).toList());
        dto.setRunningRun(recent.stream()
                .filter(run -> "STARTED".equalsIgnoreCase(run.getStatus()))
                .findFirst()
                .map(this::toSnapshot)
                .orElse(null));
        dto.setLastRun(recent.stream()
                .filter(run -> !"STARTED".equalsIgnoreCase(run.getStatus()))
                .findFirst()
                .map(this::toSnapshot)
                .orElse(null));
        dto.setNextRunAt(computeNextRunAt(scanSettings, now));

        return dto;
    }

    private Instant computeNextRunAt(ControlCenterConfig.Scan settings, Instant now) {
        if (!settings.isAutoscanEnabled() || settings.isSafeMode()) {
            return null;
        }

        int intervalMinutes = Math.max(settings.getIntervalMinutes(), 1);
        return scanRunRepository.findFirstByTriggerTypeOrderByStartedAtDesc("SCHEDULED")
                .map(run -> run.getStartedAt().plus(intervalMinutes, ChronoUnit.MINUTES))
                .map(next -> next.isAfter(now) ? next : now)
                .orElse(now);
    }

    private AutoScanStateDTO.RunSnapshot toSnapshot(ScanRun run) {
        AutoScanStateDTO.RunSnapshot snapshot = new AutoScanStateDTO.RunSnapshot();
        snapshot.setId(run.getId());
        snapshot.setStatus(run.getStatus());
        snapshot.setTriggerType(run.getTriggerType());
        snapshot.setRequestedAt(run.getRequestedAt());
        snapshot.setStartedAt(run.getStartedAt());
        snapshot.setFinishedAt(run.getFinishedAt());
        snapshot.setErrorCode(run.getErrorCode());
        snapshot.setErrorMessage(run.getNotes());
        snapshot.setCorrelationId(run.getCorrelationId());
        return snapshot;
    }
}
