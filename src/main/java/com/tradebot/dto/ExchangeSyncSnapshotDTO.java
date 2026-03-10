package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
public class ExchangeSyncSnapshotDTO {
    private UUID id;
    private UUID sessionId;
    private UUID executionId;
    private String symbol;
    private String syncType;
    private String syncStatus;
    private String traceId;
    private String errorCode;
    private String errorMessage;
    private boolean divergenceDetected;
    private boolean requiresIntervention;
    private boolean openPosition;
    private int activeOpenOrderCount;
    private int activeProtectionOrderCount;
    private boolean stopLossActive;
    private boolean takeProfitActive;
    private boolean emergencyCloseWorking;
    private boolean emergencyCloseFilled;
    private boolean protectionTriggered;
    private String entryOrderStatus;
    private String stopLossStatus;
    private String takeProfitStatus;
    private String emergencyCloseStatus;
    private BigDecimal positionQuantity;
    private BigDecimal actualFilledQty;
    private BigDecimal avgFillPrice;
    private BigDecimal entryPrice;
    private BigDecimal markPrice;
    private BigDecimal realizedGrossPnlUsdt;
    private BigDecimal realizedFeesUsdt;
    private BigDecimal realizedNetPnlUsdt;
    private BigDecimal unrealizedPnlUsdt;
    private Instant lastSuccessfulSyncAt;
    private Instant syncCompletedAt;
    private Map<String, Object> snapshot = Map.of();
}
