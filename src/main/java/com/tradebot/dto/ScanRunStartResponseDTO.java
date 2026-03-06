package com.tradebot.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class ScanRunStartResponseDTO {
    private UUID scanRunId;
    private String status;
}
