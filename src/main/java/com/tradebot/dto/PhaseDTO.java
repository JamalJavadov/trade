package com.tradebot.dto;

import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
public class PhaseDTO {
    private String phase;
    private String status;
    private Instant startedAt;
    private Instant finishedAt;
    private Long durationMs;
    private Map<String, Object> meta;
}
