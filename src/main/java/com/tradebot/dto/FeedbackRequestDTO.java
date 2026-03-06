package com.tradebot.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class FeedbackRequestDTO {
    private String userLabel; // e.g., WIN/LOSS
    private BigDecimal pnlUsdt; // Optional
    private BigDecimal rMultiple; // Optional
    private String notes; // Optional
}
