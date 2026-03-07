package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class LiveTradingPreflightDTO {
    private UUID recommendationId;
    private String symbol;
    private String side;
    private boolean allowed;
    private boolean executable;
    private boolean executionEnabled;
    private Instant checkedAt;
    private Runtime runtime = new Runtime();
    private LocalRequest localRequest = new LocalRequest();
    private Binance binance = new Binance();
    private ExchangeValidation exchangeValidation = new ExchangeValidation();
    private RecommendationPlaceabilityDTO placeability;
    private Boolean placeabilityOk;
    private List<LiveTradeBlockedReasonDTO> blockedReasons = new ArrayList<>();

    @Data
    public static class Runtime {
        private boolean liveExecutionEnabled;
        private boolean readOnly;
        private boolean tradingEnabled = true;
        private boolean runtimeReady = true;
        private boolean recommendationStale;
        private long staleThresholdSeconds;
        private Long recommendationAgeSeconds;
        private boolean duplicateSubmitBlocked;
    }

    @Data
    public static class LocalRequest {
        private boolean allowed = true;
        private String remoteAddress;
        private String forwardedFor;
        private String origin;
        private String failureReason;
    }

    @Data
    public static class Binance {
        private boolean credentialsPresent;
        private Boolean authValid;
        private Boolean futuresOrderReadOk;
        private Boolean positionModeReadOk;
        private Boolean ipAllowlistOk;
        private Boolean futuresPermissionOk;
        private Boolean timestampOk;
        private Boolean signingOk;
        private String endpointFamily;
        private String baseUrl;
        private String spotBaseUrl;
        private Long recvWindowMs;
        private Long localTimestampMs;
        private Long serverTimestampMs;
        private Long timestampSkewMs;
        private String requestIpHint;
        private String blockerCode;
        private String blockerMessage;
        private List<ProbeResult> endpointResults = new ArrayList<>();
    }

    @Data
    public static class ProbeResult {
        private String name;
        private String method;
        private String endpoint;
        private String baseUrl;
        private boolean success;
        private Integer status;
        private Integer binanceCode;
        private String binanceMessage;
        private String blockerCode;
        private String message;
    }

    @Data
    public static class ExchangeValidation {
        private boolean valid = true;
        private BigDecimal markPrice;
        private BigDecimal quantity;
        private BigDecimal entryNotionalUsdt;
        private BigDecimal tickSize;
        private BigDecimal stepSize;
        private BigDecimal minQty;
        private BigDecimal minNotional;
        private BigDecimal slStopPrice;
        private BigDecimal tpStopPrice;
        private Integer leverage;
        private String marginMode;
        private String positionMode;
        private List<String> failures = new ArrayList<>();
    }
}
