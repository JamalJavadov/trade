package com.tradebot.dto;

import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
public class StatusResponseDTO {
    // Legacy fields retained for compatibility.
    private String version;
    private Instant serverTime;
    private Instant nextScanTime;
    private String lastScanStatus;

    // Canonical UI fields.
    private String botTime;
    private long uptimeSeconds;
    private Instant lastScanTime;
    private UUID latestRecommendationId;
    private boolean scanRunning;
}
