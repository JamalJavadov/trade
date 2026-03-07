package com.tradebot.demo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
public class DemoTradeDetailDTO {
    private UUID id;
    private Instant createdAt;
    private Instant openedAt;
    private Instant closedAt;
    private String symbol;
    private String side;
    private Integer leverage;
    private BigDecimal qty;
    private BigDecimal remainingQty;
    private BigDecimal entryPrice;
    private BigDecimal slPrice;
    private BigDecimal currentSlPrice;
    private BigDecimal tp1Price;
    private BigDecimal tp2Price;
    private BigDecimal tp3Price;
    private String workingType;
    private String status;
    private String closeReason;
    private Integer stage;
    private BigDecimal riskUsdtInitial;
    private BigDecimal realizedPnlUsdt;
    private BigDecimal entryFeeUsdt;
    private BigDecimal exitFeeUsdt;
    private BigDecimal totalFeesUsdt;
    private BigDecimal lastMarkPrice;
    private BigDecimal pnlUsdt;
    @JsonProperty("rMultiple")
    private BigDecimal rMultiple;
    private String snapshotJson;
}
