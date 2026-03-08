package com.tradebot.dto;

import lombok.Data;
import java.time.Instant;

@Data
public class BinanceCredentialResponseDTO {
    private String status;
    private String authMode;
    private String credentialSource;
    private String endpointFamily;
    private Boolean executableForLiveFutures;
    private String failureCode;
    private String failureMessage;
    private String safeDetails;
    private Instant updatedAt;
    private String message;
}
