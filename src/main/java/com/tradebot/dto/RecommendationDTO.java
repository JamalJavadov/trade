package com.tradebot.dto;

import lombok.Data;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

@Data
public class RecommendationDTO {
    private UUID id;
    private UUID scanRunId;
    private String symbol;
    private String side;
    private String rationaleText;
    private BigDecimal confidenceScore;
    private Instant createdAt;
    private String status;
    private BinanceOrderFieldsDTO entryOrder;
    private BinanceOrderFieldsDTO slOrder;
    private BinanceOrderFieldsDTO tpOrder;
    private Integer leverageRecommendation;
    private String positionMode;
    private String marginMode;
    private String warning;
}
