package com.tradebot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesPositionRiskResponse {
    private String symbol;
    private String positionSide;
    private String positionAmt;
    private String entryPrice;
    private String breakEvenPrice;
    private String markPrice;
    private String unRealizedProfit;
    private String liquidationPrice;
    private String isolatedMargin;
    private String notional;
    private String marginAsset;
    private String isolatedWallet;
    private String initialMargin;
    private String maintMargin;
    private String positionInitialMargin;
    private String openOrderInitialMargin;
    private Long updateTime;
}
