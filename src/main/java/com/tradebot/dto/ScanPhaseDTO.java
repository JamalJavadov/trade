package com.tradebot.dto;

import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
public class ScanPhaseDTO {
    private String name;
    private String status;
    private Instant startedAt;
    private Map<String, Object> metadata;
}
