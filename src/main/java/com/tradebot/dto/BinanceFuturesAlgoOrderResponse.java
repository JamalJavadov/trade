package com.tradebot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesAlgoOrderResponse {
    private Long algoId;
    private String clientAlgoId;
    private String algoType;
    private String algoStatus;
    private String orderType;
    private String symbol;
    private String side;
    private String positionSide;
    private String timeInForce;
    private String quantity;
    private String executedQty;
    private String avgPrice;
    private String actualOrderId;
    private String actualPrice;
    private String triggerPrice;
    private String price;
    private String workingType;
    private Boolean closePosition;
    private Boolean priceProtect;
    private Boolean reduceOnly;
    private Long createTime;
    private Long updateTime;
    private Long triggerTime;
}
