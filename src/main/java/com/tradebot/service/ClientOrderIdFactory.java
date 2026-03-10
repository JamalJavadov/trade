package com.tradebot.service;

import com.tradebot.entity.LiveTradeExecution;
import org.springframework.stereotype.Component;

@Component
public class ClientOrderIdFactory {

    public String entryOrderId(LiveTradeExecution execution) {
        return build(execution, "entry");
    }

    public String stopLossOrderId(LiveTradeExecution execution) {
        return build(execution, "sl");
    }

    public String takeProfitOrderId(LiveTradeExecution execution) {
        return build(execution, "tp");
    }

    public String emergencyCloseOrderId(LiveTradeExecution execution) {
        return build(execution, "close");
    }

    private String build(LiveTradeExecution execution, String suffix) {
        String compact = execution.getId().toString().replace("-", "");
        String shortId = compact.substring(0, Math.min(12, compact.length()));
        String prefix = execution.getTriggerMode() != null && execution.getTriggerMode().name().contains("AUTO")
                ? "tb-auto"
                : "tb-live";
        return prefix + "-" + shortId + "-" + suffix;
    }
}
