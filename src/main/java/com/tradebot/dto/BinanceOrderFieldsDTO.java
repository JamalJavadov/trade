package com.tradebot.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class BinanceOrderFieldsDTO {
    private String symbol;
    private String side;
    private String type;
    private BigDecimal quantity;
    private BigDecimal price; // Optional for LIMIT
    private BigDecimal stopPrice;
    private Boolean reduceOnly;
    private Boolean closePosition;
    private String workingType;
    private String timeInForce;
    private String positionSide;
}
