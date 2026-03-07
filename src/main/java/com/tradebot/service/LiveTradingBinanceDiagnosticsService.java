package com.tradebot.service;

import com.tradebot.client.BinanceClient;
import com.tradebot.exception.BotReadOnlyException;
import com.tradebot.dto.LiveTradeBlockedReasonDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.service.BinanceErrorClassifier.BinanceErrorDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Service
@RequiredArgsConstructor
public class LiveTradingBinanceDiagnosticsService {

    private static final String DEFAULT_PROBE_SYMBOL = "BTCUSDT";

    private final BinanceClient binanceClient;

    public LiveTradingPreflightDTO.Binance evaluate(String symbol) {
        LiveTradingPreflightDTO.Binance diagnostics = new LiveTradingPreflightDTO.Binance();
        diagnostics.setCredentialsPresent(binanceClient.hasTradingCredentials());
        diagnostics.setEndpointFamily("BINANCE_FUTURES");
        diagnostics.setBaseUrl(binanceClient.getFuturesBaseUrl());
        diagnostics.setSpotBaseUrl(binanceClient.getSpotBaseUrl());
        diagnostics.setRecvWindowMs(binanceClient.getSignedRecvWindowMs());
        diagnostics.setLocalTimestampMs(System.currentTimeMillis());
        diagnostics.setAuthValid(Boolean.FALSE);

        probeServerTime(diagnostics);

        if (!diagnostics.isCredentialsPresent()) {
            diagnostics.setBlockerCode(LiveTradingBlockerCodes.BINANCE_AUTH_INVALID);
            diagnostics.setBlockerMessage("Binance API key/secret are missing.");
            diagnostics.setSigningOk(Boolean.FALSE);
            diagnostics.setTimestampOk(Boolean.FALSE);
            diagnostics.setFuturesPermissionOk(Boolean.FALSE);
            diagnostics.setIpAllowlistOk(null);
            return diagnostics;
        }

        String probeSymbol = normalizeSymbol(symbol);
        LiveTradingPreflightDTO.ProbeResult futuresOrderProbe = probe(
                "futuresOrderRead",
                "GET",
                "/fapi/v1/allOrders",
                binanceClient.getFuturesBaseUrl(),
                () -> binanceClient.getRecentFuturesOrders(probeSymbol, 1));
        diagnostics.getEndpointResults().add(futuresOrderProbe);
        diagnostics.setFuturesOrderReadOk(futuresOrderProbe.isSuccess());

        if (!futuresOrderProbe.isSuccess()) {
            applyPrimaryProbeFailure(diagnostics, futuresOrderProbe, true);
        }

        LiveTradingPreflightDTO.ProbeResult positionModeProbe = probe(
                "positionModeRead",
                "GET",
                "/fapi/v1/positionSide/dual",
                binanceClient.getFuturesBaseUrl(),
                binanceClient::getDualSidePositionMode);
        diagnostics.getEndpointResults().add(positionModeProbe);
        diagnostics.setPositionModeReadOk(positionModeProbe.isSuccess());

        if (!positionModeProbe.isSuccess()) {
            applyPrimaryProbeFailure(diagnostics, positionModeProbe, false);
        }

        boolean authValid = Boolean.TRUE.equals(diagnostics.getFuturesOrderReadOk())
                && Boolean.TRUE.equals(diagnostics.getPositionModeReadOk());
        diagnostics.setAuthValid(authValid);
        if (authValid) {
            diagnostics.setIpAllowlistOk(Boolean.TRUE);
            diagnostics.setFuturesPermissionOk(Boolean.TRUE);
            diagnostics.setTimestampOk(Boolean.TRUE);
            diagnostics.setSigningOk(Boolean.TRUE);
            diagnostics.setBlockerCode(null);
            diagnostics.setBlockerMessage(null);
        }
        return diagnostics;
    }

    public LiveTradeBlockedReasonDTO classifyExecutionFailure(Throwable error) {
        if (error instanceof WebClientRequestException requestException) {
            return new LiveTradeBlockedReasonDTO(
                    LiveTradingBlockerCodes.BINANCE_NETWORK,
                    BinanceErrorClassifier.defaultMessage(LiveTradingBlockerCodes.BINANCE_NETWORK, null),
                    "binance",
                    detailsOf(
                            "host", requestException.getUri() != null ? requestException.getUri().getHost() : null,
                            "path", requestException.getUri() != null ? requestException.getUri().getPath() : null));
        }
        if (error instanceof WebClientResponseException responseException) {
            BinanceErrorDetails details = BinanceErrorClassifier.from(responseException);
            String blockerCode = BinanceErrorClassifier.classify(details);
            return new LiveTradeBlockedReasonDTO(
                    blockerCode,
                    BinanceErrorClassifier.defaultMessage(blockerCode, details),
                    "binance",
                    detailsMap(details));
        }
        if (error instanceof BotReadOnlyException || containsReadOnlyMessage(error != null ? error.getMessage() : null)) {
            return new LiveTradeBlockedReasonDTO(
                    LiveTradingBlockerCodes.BOT_READ_ONLY,
                    "Trading is disabled. Bot is in READ-ONLY mode.",
                    "runtime",
                    Map.of());
        }
        if (error instanceof IllegalStateException illegalStateException) {
            String message = illegalStateException.getMessage();
            if (containsIgnoreCase(message, "sign binance request")) {
                return new LiveTradeBlockedReasonDTO(
                        LiveTradingBlockerCodes.BINANCE_SIGNING_FAILED,
                        BinanceErrorClassifier.defaultMessage(LiveTradingBlockerCodes.BINANCE_SIGNING_FAILED, null),
                        "binance",
                        detailsOf("exceptionType", illegalStateException.getClass().getSimpleName()));
            }
            if (containsIgnoreCase(message, "trading credentials are missing")
                    || containsIgnoreCase(message, "api key/secret are missing")) {
                return new LiveTradeBlockedReasonDTO(
                        LiveTradingBlockerCodes.BINANCE_AUTH_INVALID,
                        "Binance API key/secret are missing.",
                        "binance",
                        detailsOf("exceptionType", illegalStateException.getClass().getSimpleName()));
            }
        }
        return new LiveTradeBlockedReasonDTO(
                LiveTradingBlockerCodes.LIVE_EXECUTION_FAILED,
                error != null && error.getMessage() != null && !error.getMessage().isBlank()
                        ? error.getMessage()
                        : "Live execution failed before Binance confirmation.",
                "execution",
                detailsOf("exceptionType", error != null ? error.getClass().getSimpleName() : "Unknown"));
    }

    private void probeServerTime(LiveTradingPreflightDTO.Binance diagnostics) {
        LiveTradingPreflightDTO.ProbeResult serverTimeProbe = probe(
                "futuresServerTime",
                "GET",
                "/fapi/v1/time",
                binanceClient.getFuturesBaseUrl(),
                binanceClient::getFuturesServerTime);
        diagnostics.getEndpointResults().add(serverTimeProbe);
        if (!serverTimeProbe.isSuccess()) {
            return;
        }
        Long serverTimeMs = extractServerTime(serverTimeProbe.getMessage());
        if (serverTimeMs == null) {
            try {
                serverTimeMs = binanceClient.getFuturesServerTime();
            } catch (Exception ignored) {
                serverTimeMs = null;
            }
        }
        diagnostics.setServerTimestampMs(serverTimeMs);
        if (serverTimeMs != null && diagnostics.getLocalTimestampMs() != null) {
            diagnostics.setTimestampSkewMs(serverTimeMs - diagnostics.getLocalTimestampMs());
        }
    }

    private void applyPrimaryProbeFailure(LiveTradingPreflightDTO.Binance diagnostics,
            LiveTradingPreflightDTO.ProbeResult probe,
            boolean allowSpotCrossCheck) {
        if (probe == null || probe.isSuccess()) {
            return;
        }

        String blockerCode = probe.getBlockerCode();
        String blockerMessage = probe.getMessage();

        if (allowSpotCrossCheck && LiveTradingBlockerCodes.BINANCE_AUTH_INVALID.equals(blockerCode)) {
            LiveTradingPreflightDTO.ProbeResult spotProbe = probe(
                    "spotAccountRead",
                    "GET",
                    "/api/v3/account",
                    binanceClient.getSpotBaseUrl(),
                    binanceClient::getSpotAccount);
            diagnostics.getEndpointResults().add(spotProbe);
            if (spotProbe.isSuccess()) {
                blockerCode = LiveTradingBlockerCodes.BINANCE_FUTURES_PERMISSION_MISSING;
                blockerMessage = "Spot auth succeeds, but Binance Futures signed access is missing for this key.";
                diagnostics.setFuturesPermissionOk(Boolean.FALSE);
                diagnostics.setIpAllowlistOk(Boolean.TRUE);
                diagnostics.setSigningOk(Boolean.TRUE);
                diagnostics.setTimestampOk(Boolean.TRUE);
            } else {
                String requestIpHint = firstNonBlank(probe.getBinanceMessage(), spotProbe.getBinanceMessage());
                String hintedIp = extractRequestIpHint(probe, spotProbe);
                if (hintedIp != null) {
                    blockerCode = LiveTradingBlockerCodes.BINANCE_IP_NOT_ALLOWED;
                    blockerMessage = "Binance rejected the backend host IP. Verify the Binance trusted IP allowlist.";
                    diagnostics.setIpAllowlistOk(Boolean.FALSE);
                    diagnostics.setRequestIpHint(hintedIp);
                } else {
                    blockerCode = LiveTradingBlockerCodes.BINANCE_AUTH_INVALID;
                    blockerMessage = "Binance signed auth failed for both Futures and Spot probes.";
                    diagnostics.setIpAllowlistOk(null);
                }
                diagnostics.setFuturesPermissionOk(Boolean.FALSE);
                diagnostics.setSigningOk(Boolean.FALSE);
                diagnostics.setTimestampOk(Boolean.FALSE);
                if (requestIpHint != null && diagnostics.getRequestIpHint() == null) {
                    diagnostics.setRequestIpHint(extractRequestIpHint(probe, spotProbe));
                }
            }
        } else {
            if (LiveTradingBlockerCodes.BINANCE_IP_NOT_ALLOWED.equals(blockerCode)) {
                diagnostics.setIpAllowlistOk(Boolean.FALSE);
            }
            if (LiveTradingBlockerCodes.BINANCE_TIMESTAMP_INVALID.equals(blockerCode)) {
                diagnostics.setTimestampOk(Boolean.FALSE);
            }
            if (LiveTradingBlockerCodes.BINANCE_SIGNING_FAILED.equals(blockerCode)) {
                diagnostics.setSigningOk(Boolean.FALSE);
            }
            if (LiveTradingBlockerCodes.BINANCE_ENDPOINT_MISCONFIGURED.equals(blockerCode)) {
                diagnostics.setFuturesPermissionOk(Boolean.FALSE);
            }
            String hintedIp = extractRequestIpHint(probe);
            if (hintedIp != null) {
                diagnostics.setRequestIpHint(hintedIp);
            }
        }

        if (diagnostics.getBlockerCode() == null
                || LiveTradingBlockerCodes.priority(blockerCode)
                        < LiveTradingBlockerCodes.priority(diagnostics.getBlockerCode())) {
            diagnostics.setBlockerCode(blockerCode);
            diagnostics.setBlockerMessage(blockerMessage);
        }
    }

    private LiveTradingPreflightDTO.ProbeResult probe(String name,
            String method,
            String endpoint,
            String baseUrl,
            Callable<?> action) {
        LiveTradingPreflightDTO.ProbeResult result = new LiveTradingPreflightDTO.ProbeResult();
        result.setName(name);
        result.setMethod(method);
        result.setEndpoint(endpoint);
        result.setBaseUrl(baseUrl);
        try {
            Object response = action.call();
            result.setSuccess(true);
            result.setMessage(summarizeResponse(response));
            return result;
        } catch (WebClientResponseException responseException) {
            BinanceErrorDetails details = BinanceErrorClassifier.from(responseException);
            result.setSuccess(false);
            result.setStatus(details.status());
            result.setBinanceCode(details.binanceCode());
            result.setBinanceMessage(details.binanceMessage());
            result.setBlockerCode(BinanceErrorClassifier.classify(details));
            result.setMessage(BinanceErrorClassifier.defaultMessage(result.getBlockerCode(), details));
            return result;
        } catch (WebClientRequestException requestException) {
            result.setSuccess(false);
            result.setBlockerCode(LiveTradingBlockerCodes.BINANCE_NETWORK);
            result.setMessage("Binance network request timed out or failed.");
            return result;
        } catch (Exception exception) {
            LiveTradeBlockedReasonDTO failure = classifyExecutionFailure(exception);
            result.setSuccess(false);
            result.setBlockerCode(failure.getCode());
            result.setMessage(failure.getMessage());
            return result;
        }
    }

    private Map<String, Object> detailsMap(BinanceErrorDetails details) {
        Map<String, Object> detailsMap = new LinkedHashMap<>();
        detailsMap.put("host", details.host());
        detailsMap.put("path", details.path());
        detailsMap.put("status", details.status());
        detailsMap.put("binanceCode", details.binanceCode());
        detailsMap.put("binanceMessage", details.binanceMessage());
        detailsMap.put("requestIpHint", details.requestIpHint());
        return detailsMap;
    }

    private String summarizeResponse(Object response) {
        if (response == null) {
            return "OK";
        }
        if (response instanceof Number number) {
            return String.valueOf(number.longValue());
        }
        if (response instanceof Boolean bool) {
            return String.valueOf(bool);
        }
        if (response instanceof List<?> list) {
            return "items=" + list.size();
        }
        if (response instanceof Map<?, ?> map) {
            return "keys=" + map.keySet();
        }
        return response.getClass().getSimpleName();
    }

    private Long extractServerTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return DEFAULT_PROBE_SYMBOL;
        }
        return symbol.trim().toUpperCase();
    }

    private String extractRequestIpHint(LiveTradingPreflightDTO.ProbeResult... probes) {
        if (probes == null) {
            return null;
        }
        for (LiveTradingPreflightDTO.ProbeResult probe : probes) {
            if (probe == null) {
                continue;
            }
            String message = probe.getBinanceMessage();
            if (message == null || message.isBlank()) {
                continue;
            }
            int markerIndex = message.toLowerCase().indexOf("request ip:");
            if (markerIndex >= 0) {
                return message.substring(markerIndex + "request ip:".length()).trim();
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean containsReadOnlyMessage(String message) {
        return message != null && message.toLowerCase().contains("read-only mode");
    }

    private boolean containsIgnoreCase(String value, String fragment) {
        return value != null && fragment != null && value.toLowerCase().contains(fragment.toLowerCase());
    }

    private Map<String, Object> detailsOf(Object... entries) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (entries == null) {
            return details;
        }
        for (int index = 0; index + 1 < entries.length; index += 2) {
            Object key = entries[index];
            if (!(key instanceof String stringKey)) {
                continue;
            }
            Object value = entries[index + 1];
            if (value != null) {
                details.put(stringKey, value);
            }
        }
        return details;
    }
}
