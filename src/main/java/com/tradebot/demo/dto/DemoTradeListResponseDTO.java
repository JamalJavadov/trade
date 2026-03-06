package com.tradebot.demo.dto;

import lombok.Data;

import java.util.List;

@Data
public class DemoTradeListResponseDTO {
    private int limit;
    private int offset;
    private long total;
    private List<DemoTradeSummaryDTO> trades;
}
