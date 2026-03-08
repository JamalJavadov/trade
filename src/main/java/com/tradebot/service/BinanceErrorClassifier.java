package com.tradebot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BinanceErrorClassifier {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern REQUEST_IP_PATTERN = Pattern.compile("request ip:\\s*([^,\\s]+)",
            Pattern.CASE_INSENSITIVE);

    private BinanceErrorClassifier() {
    }

    public static BinanceErrorDetails from(WebClientResponseException exception) {
        if (exception == null) {
            return new BinanceErrorDetails(null, null, null, null, null, null, null);
        }

        String host = exception.getRequest() != null && exception.getRequest().getURI() != null
                ? exception.getRequest().getURI().getHost()
                : null;
        String path = exception.getRequest() != null && exception.getRequest().getURI() != null
                ? exception.getRequest().getURI().getPath()
                : null;
        String body = exception.getResponseBodyAsString();
        Integer binanceCode = null;
        String binanceMessage = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(body);
                if (root.has("code") && root.get("code").canConvertToInt()) {
                    binanceCode = root.get("code").intValue();
                }
                if (root.has("msg") && root.get("msg").isTextual()) {
                    binanceMessage = root.get("msg").asText();
                }
            } catch (Exception ignored) {
                // Keep raw body in details even if Binance returned non-JSON text.
            }
        }
        String requestIpHint = extractRequestIpHint(binanceMessage == null ? body : binanceMessage);
        return new BinanceErrorDetails(
                host,
                path,
                exception.getStatusCode().value(),
                binanceCode,
                binanceMessage,
                body,
                requestIpHint);
    }

    public static boolean isAmbiguousAuth(BinanceErrorDetails details) {
        if (details == null) {
            return false;
        }
        if (details.binanceCode() != null && (details.binanceCode() == -2014 || details.binanceCode() == -2015)) {
            return true;
        }
        if (details.status() != null && (details.status() == 401 || details.status() == 403)) {
            return true;
        }
        String message = normalizedMessage(details);
        return message.contains("invalid api-key") || message.contains("api-key format invalid")
                || message.contains("signature for this request is not valid");
    }

    public static String classify(BinanceErrorDetails details) {
        if (details == null) {
            return LiveTradingBlockerCodes.BINANCE_AUTH_INVALID;
        }
        Integer status = details.status();
        if (status != null && status == 429) {
            return LiveTradingBlockerCodes.BINANCE_RATE_LIMIT;
        }
        if (isIpNotAllowed(details)) {
            return LiveTradingBlockerCodes.BINANCE_IP_NOT_ALLOWED;
        }
        if (isEndpointMisconfigured(details)) {
            return LiveTradingBlockerCodes.BINANCE_ENDPOINT_MISCONFIGURED;
        }
        if (isTimestampInvalid(details)) {
            return LiveTradingBlockerCodes.BINANCE_TIMESTAMP_INVALID;
        }
        if (isSigningFailed(details)) {
            return LiveTradingBlockerCodes.BINANCE_SIGNING_FAILED;
        }
        if (isAmbiguousAuth(details)) {
            return LiveTradingBlockerCodes.BINANCE_AUTH_INVALID;
        }
        return LiveTradingBlockerCodes.BINANCE_REJECTED;
    }

    public static String defaultMessage(String blockerCode, BinanceErrorDetails details) {
        return switch (blockerCode) {
            case LiveTradingBlockerCodes.BINANCE_TIMESTAMP_INVALID ->
                "Binance rejected the signed request because the local timestamp is outside recvWindow.";
            case LiveTradingBlockerCodes.BINANCE_SIGNING_FAILED ->
                "Binance rejected the signed request for the selected credential type.";
            case LiveTradingBlockerCodes.BINANCE_ENDPOINT_MISCONFIGURED ->
                "Binance endpoint family/path is misconfigured for Futures signed requests.";
            case LiveTradingBlockerCodes.BINANCE_IP_NOT_ALLOWED ->
                "Binance rejected the backend host IP. Verify the Binance trusted IP allowlist.";
            case LiveTradingBlockerCodes.BINANCE_FUTURES_PERMISSION_MISSING ->
                "Binance authenticated the request, but this API key cannot trade USD-M Futures.";
            case LiveTradingBlockerCodes.BINANCE_RATE_LIMIT ->
                "Binance rate limit exceeded during live-trading diagnostics.";
            case LiveTradingBlockerCodes.BINANCE_NETWORK ->
                "Binance network request timed out or lost connectivity.";
            case LiveTradingBlockerCodes.BINANCE_AUTH_INVALID ->
                "Binance authentication failed for the selected credential type. Check the API key, matching secret/private key, Futures permission, and any trusted IP policy.";
            default -> details != null && details.binanceMessage() != null && !details.binanceMessage().isBlank()
                    ? details.binanceMessage()
                    : "Binance rejected the live-trading request.";
        };
    }

    public static boolean isTimestampInvalid(BinanceErrorDetails details) {
        if (details == null) {
            return false;
        }
        if (details.binanceCode() != null && details.binanceCode() == -1021) {
            return true;
        }
        String message = normalizedMessage(details);
        return message.contains("timestamp") && message.contains("recvwindow");
    }

    public static boolean isSigningFailed(BinanceErrorDetails details) {
        if (details == null) {
            return false;
        }
        if (details.binanceCode() != null && details.binanceCode() == -1022) {
            return true;
        }
        String message = normalizedMessage(details);
        return message.contains("signature for this request is not valid");
    }

    public static boolean isIpNotAllowed(BinanceErrorDetails details) {
        if (details == null) {
            return false;
        }
        if (details.requestIpHint() != null && !details.requestIpHint().isBlank()) {
            return true;
        }
        String message = normalizedMessage(details);
        return message.contains("request ip:")
                || message.contains("not in the api whitelist")
                || message.contains("api whitelist");
    }

    public static boolean isEndpointMisconfigured(BinanceErrorDetails details) {
        if (details == null) {
            return false;
        }
        if (details.status() != null && (details.status() == 404 || details.status() == 405)) {
            return true;
        }
        String message = normalizedMessage(details);
        return message.contains("not found")
                || message.contains("unknown order sent")
                || message.contains("invalid endpoint")
                || message.contains("http 404");
    }

    private static String normalizedMessage(BinanceErrorDetails details) {
        if (details == null) {
            return "";
        }
        String candidate = details.binanceMessage() != null && !details.binanceMessage().isBlank()
                ? details.binanceMessage()
                : details.rawBody();
        return candidate == null ? "" : candidate.toLowerCase();
    }

    private static String extractRequestIpHint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = REQUEST_IP_PATTERN.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    public record BinanceErrorDetails(
            String host,
            String path,
            Integer status,
            Integer binanceCode,
            String binanceMessage,
            String rawBody,
            String requestIpHint) {
    }
}
