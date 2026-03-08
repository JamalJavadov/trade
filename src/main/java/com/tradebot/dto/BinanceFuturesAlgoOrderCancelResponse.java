package com.tradebot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesAlgoOrderCancelResponse {
    private Long algoId;
    private String clientAlgoId;
    private String code;
    private String msg;
}
