package com.tradebot.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceExchangeInfoResponse {
    private List<SymbolInfo> symbols;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SymbolInfo {
        private String symbol;
        private String status;
        private String contractType;
        private String quoteAsset;
        private List<Filter> filters;

        public BigDecimal getTickSize() {
            if (filters == null)
                return BigDecimal.ZERO;
            for (Filter f : filters) {
                if ("PRICE_FILTER".equals(f.getFilterType())) {
                    return f.getTickSize() != null ? new BigDecimal(f.getTickSize()) : BigDecimal.ZERO;
                }
            }
            return BigDecimal.ZERO;
        }

        public BigDecimal getStepSize() {
            if (filters == null)
                return BigDecimal.ZERO;
            for (Filter f : filters) {
                if ("LOT_SIZE".equals(f.getFilterType())) {
                    return f.getStepSize() != null ? new BigDecimal(f.getStepSize()) : BigDecimal.ZERO;
                }
            }
            return BigDecimal.ZERO;
        }

        public BigDecimal getMarketStepSize() {
            if (filters == null)
                return BigDecimal.ZERO;
            for (Filter f : filters) {
                if ("MARKET_LOT_SIZE".equals(f.getFilterType())) {
                    return f.getStepSize() != null ? new BigDecimal(f.getStepSize()) : BigDecimal.ZERO;
                }
            }
            return BigDecimal.ZERO;
        }

        public BigDecimal getMinQty() {
            if (filters == null)
                return BigDecimal.ZERO;
            for (Filter f : filters) {
                if ("LOT_SIZE".equals(f.getFilterType())) {
                    return f.getMinQty() != null ? new BigDecimal(f.getMinQty()) : BigDecimal.ZERO;
                }
            }
            return BigDecimal.ZERO;
        }

        public BigDecimal getMarketMinQty() {
            if (filters == null)
                return BigDecimal.ZERO;
            for (Filter f : filters) {
                if ("MARKET_LOT_SIZE".equals(f.getFilterType())) {
                    return f.getMinQty() != null ? new BigDecimal(f.getMinQty()) : BigDecimal.ZERO;
                }
            }
            return BigDecimal.ZERO;
        }

        public BigDecimal getMinNotional() {
            if (filters == null)
                return BigDecimal.ZERO;
            for (Filter f : filters) {
                if ("MIN_NOTIONAL".equals(f.getFilterType()) || "NOTIONAL".equals(f.getFilterType())) {
                    String value = f.getNotional() != null ? f.getNotional() : f.getMinNotional();
                    return value != null ? new BigDecimal(value) : BigDecimal.ZERO;
                }
            }
            return BigDecimal.ZERO;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Filter {
        private String filterType;
        private String tickSize;
        private String stepSize;
        private String minQty;
        private String minNotional;
        private String notional;
    }
}
