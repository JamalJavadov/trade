package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class AutoScanStateDTO {
    private boolean autoscanEnabled;
    private boolean safeMode;
    private int intervalMinutes;
    private Instant nextRunAt;
    private RunSnapshot runningRun;
    private RunSnapshot lastRun;
    private List<RunSnapshot> recentRuns = new ArrayList<>();
    private Instant serverTime;

    @Data
    public static class RunSnapshot {
        private UUID id;
        private String status;
        private String triggerType;
        private Instant requestedAt;
        private Instant startedAt;
        private Instant finishedAt;
        private String errorCode;
        private String errorMessage;
        private String correlationId;
    }
}
