package com.tradebot.demo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
public class DemoTradeSummaryDTO {
    private UUID id;
    private Instant openedAt;
    private Instant closedAt;
    private String symbol;
    private String side;
    private String status;
    private String closeReason;
    private Integer stage;
    private BigDecimal remainingQty;
    private BigDecimal pnlUsdt;
    @JsonProperty("rMultiple")
    private BigDecimal rMultiple;
}
