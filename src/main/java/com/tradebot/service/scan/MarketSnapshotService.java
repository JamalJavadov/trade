package com.tradebot.service.scan;

import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.dto.BinanceExchangeInfoResponse;
import com.tradebot.dto.BinanceTicker24hResponse;
import com.tradebot.dto.Candle;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MarketSnapshotService {

    public FrozenSymbolSnapshot freeze(
            UUID scanRunId,
            String traceId,
            BinanceTicker24hResponse ticker,
            int rankInUniverse,
            BinanceExchangeInfoResponse.SymbolInfo symbolInfo,
            List<Candle> executionCandles,
            List<Candle> biasCandles,
            ControlCenterConfig controlCenterConfig) {

        Map<String, Object> exchangeMetadata = new LinkedHashMap<>();
        if (symbolInfo != null) {
            exchangeMetadata.put("symbol", symbolInfo.getSymbol());
            exchangeMetadata.put("status", symbolInfo.getStatus());
            exchangeMetadata.put("contractType", symbolInfo.getContractType());
            exchangeMetadata.put("quoteAsset", symbolInfo.getQuoteAsset());
            exchangeMetadata.put("tickSize", symbolInfo.getTickSize());
            exchangeMetadata.put("stepSize", symbolInfo.getStepSize());
            exchangeMetadata.put("minQty", symbolInfo.getMinQty());
        }

        return new FrozenSymbolSnapshot(
                scanRunId,
                traceId,
                ticker.getSymbol(),
                rankInUniverse,
                ticker.getQuoteVolume(),
                java.time.Instant.now(),
                controlCenterConfig.getStrategyLocks().getExecutionTf(),
                controlCenterConfig.getStrategyLocks().getBiasTf(),
                Collections.unmodifiableMap(new LinkedHashMap<>(exchangeMetadata)),
                executionCandles == null ? List.of() : List.copyOf(executionCandles),
                biasCandles == null ? List.of() : List.copyOf(biasCandles));
    }
}
