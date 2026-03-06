package com.tradebot.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class BinanceTicker24hResponse {
    private String symbol;
    private BigDecimal quoteVolume;
}
