package com.tradebot.service;

import com.tradebot.dto.BinanceFuturesAlgoOrderResponse;
import com.tradebot.dto.BinanceFuturesOrderResponse;
import com.tradebot.dto.BinanceFuturesPositionRiskResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BinanceExecutionResponseParser {

    public Map<String, Object> orderSummary(BinanceFuturesOrderResponse response) {
        if (response == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", response.getOrderId());
        summary.put("clientOrderId", response.getClientOrderId());
        summary.put("status", response.getStatus());
        summary.put("executedQty", response.getExecutedQty());
        summary.put("origQty", response.getOrigQty());
        summary.put("avgPrice", response.getAvgPrice());
        summary.put("cumQuote", response.getCumQuote());
        summary.put("stopPrice", response.getStopPrice());
        summary.put("workingType", response.getWorkingType());
        summary.put("positionSide", response.getPositionSide());
        summary.put("closePosition", response.getClosePosition());
        summary.put("reduceOnly", response.getReduceOnly());
        summary.put("priceProtect", response.getPriceProtect());
        summary.put("updateTime", response.getUpdateTime());
        return summary;
    }

    public Map<String, Object> algoSummary(BinanceFuturesAlgoOrderResponse response) {
        if (response == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("algoId", response.getAlgoId());
        summary.put("clientAlgoId", response.getClientAlgoId());
        summary.put("algoType", response.getAlgoType());
        summary.put("algoStatus", response.getAlgoStatus());
        summary.put("orderType", response.getOrderType());
        summary.put("symbol", response.getSymbol());
        summary.put("side", response.getSide());
        summary.put("positionSide", response.getPositionSide());
        summary.put("quantity", response.getQuantity());
        summary.put("executedQty", response.getExecutedQty());
        summary.put("avgPrice", response.getAvgPrice());
        summary.put("actualOrderId", response.getActualOrderId());
        summary.put("actualPrice", response.getActualPrice());
        summary.put("triggerPrice", response.getTriggerPrice());
        summary.put("workingType", response.getWorkingType());
        summary.put("closePosition", response.getClosePosition());
        summary.put("reduceOnly", response.getReduceOnly());
        summary.put("priceProtect", response.getPriceProtect());
        summary.put("createTime", response.getCreateTime());
        summary.put("updateTime", response.getUpdateTime());
        summary.put("triggerTime", response.getTriggerTime());
        return summary;
    }

    public Map<String, Object> positionSummary(BinanceFuturesPositionRiskResponse response) {
        if (response == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("symbol", response.getSymbol());
        summary.put("positionSide", response.getPositionSide());
        summary.put("positionAmt", response.getPositionAmt());
        summary.put("entryPrice", response.getEntryPrice());
        summary.put("breakEvenPrice", response.getBreakEvenPrice());
        summary.put("markPrice", response.getMarkPrice());
        summary.put("unRealizedProfit", response.getUnRealizedProfit());
        summary.put("notional", response.getNotional());
        summary.put("updateTime", response.getUpdateTime());
        return summary;
    }

    public Map<String, Object> mergeSection(Object existingValue, Map<String, Object> latest) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existingValue instanceof Map<?, ?> existingMap) {
            existingMap.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    merged.put(stringKey, value);
                }
            });
        }
        if (latest != null) {
            merged.putAll(latest);
        }
        return merged;
    }

    public BigDecimal parseDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal signedDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    public BigDecimal positiveDecimal(String value) {
        BigDecimal parsed = signedDecimal(value);
        return parsed != null && parsed.compareTo(BigDecimal.ZERO) > 0 ? parsed : null;
    }

    public boolean isFilled(BinanceFuturesOrderResponse response) {
        return response != null && "FILLED".equalsIgnoreCase(response.getStatus());
    }

    public boolean isWorking(BinanceFuturesOrderResponse response) {
        return response != null && ("NEW".equalsIgnoreCase(response.getStatus())
                || "PARTIALLY_FILLED".equalsIgnoreCase(response.getStatus()));
    }

    public boolean isAlgoActive(BinanceFuturesAlgoOrderResponse response, List<BinanceFuturesAlgoOrderResponse> openOrders) {
        if (response == null) {
            return false;
        }
        if (findOpenAlgoOrder(response.getClientAlgoId(), response.getAlgoId(), openOrders) != null) {
            return true;
        }
        return "NEW".equalsIgnoreCase(response.getAlgoStatus())
                || "WORKING".equalsIgnoreCase(response.getAlgoStatus());
    }

    public boolean isTriggered(BinanceFuturesAlgoOrderResponse response) {
        return response != null && response.getTriggerTime() != null && response.getTriggerTime() > 0;
    }

    public boolean hasOpenPosition(BinanceFuturesPositionRiskResponse position) {
        return absolutePositionQty(position).compareTo(BigDecimal.ZERO) > 0;
    }

    public BigDecimal absolutePositionQty(BinanceFuturesPositionRiskResponse position) {
        BigDecimal qty = signedDecimal(position != null ? position.getPositionAmt() : null);
        return qty == null ? BigDecimal.ZERO : qty.abs();
    }

    public BinanceFuturesAlgoOrderResponse findOpenAlgoOrder(String clientAlgoId,
            Long algoId,
            List<BinanceFuturesAlgoOrderResponse> openAlgoOrders) {
        if (openAlgoOrders == null) {
            return null;
        }
        return openAlgoOrders.stream()
                .filter(order -> {
                    if (algoId != null && algoId.equals(order.getAlgoId())) {
                        return true;
                    }
                    return clientAlgoId != null && clientAlgoId.equals(order.getClientAlgoId());
                })
                .findFirst()
                .orElse(null);
    }

    public Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
