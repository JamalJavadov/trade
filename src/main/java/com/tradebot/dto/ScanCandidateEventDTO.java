package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class ScanCandidateEventDTO {
    private String symbol;
    private String stage;
    private Integer seq;
    private String status;
    private Instant ts;
    private Map<String, Object> payload;
}
