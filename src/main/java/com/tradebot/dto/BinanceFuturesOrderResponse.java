package com.tradebot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesOrderResponse {
    private Long orderId;
    private String symbol;
    private String status;
    private String clientOrderId;
    private String side;
    private String type;
    private String positionSide;
    private String executedQty;
    private String origQty;
    private String avgPrice;
    private String cumQuote;
    private String stopPrice;
    private String workingType;
    private Boolean reduceOnly;
    private Boolean closePosition;
    private Boolean priceProtect;
    private Long updateTime;
}
