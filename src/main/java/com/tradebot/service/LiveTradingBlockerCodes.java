package com.tradebot.service;

import java.util.Map;

public final class LiveTradingBlockerCodes {

    public static final String BINANCE_AUTH_INVALID = "BINANCE_AUTH_INVALID";
    public static final String BINANCE_IP_NOT_ALLOWED = "BINANCE_IP_NOT_ALLOWED";
    public static final String BINANCE_FUTURES_PERMISSION_MISSING = "BINANCE_FUTURES_PERMISSION_MISSING";
    public static final String BINANCE_TIMESTAMP_INVALID = "BINANCE_TIMESTAMP_INVALID";
    public static final String BINANCE_SIGNING_FAILED = "BINANCE_SIGNING_FAILED";
    public static final String BINANCE_ENDPOINT_MISCONFIGURED = "BINANCE_ENDPOINT_MISCONFIGURED";
    public static final String BINANCE_NETWORK = "BINANCE_NETWORK";
    public static final String LIVE_EXECUTION_DISABLED = "LIVE_EXECUTION_DISABLED";
    public static final String BOT_READ_ONLY = "BOT_READ_ONLY";
    public static final String LOCAL_MUTATION_BLOCKED = "LOCAL_MUTATION_BLOCKED";
    public static final String RUNTIME_PERMISSION_DISABLED = "RUNTIME_PERMISSION_DISABLED";
    public static final String DUPLICATE_SUBMIT_BLOCKED = "DUPLICATE_SUBMIT_BLOCKED";
    public static final String PLACEABILITY_FAILED = "PLACEABILITY_FAILED";
    public static final String RECOMMENDATION_STALE = "RECOMMENDATION_STALE";
    public static final String EXCHANGE_FILTER_INVALID = "EXCHANGE_FILTER_INVALID";
    public static final String MISSING_ORDER_FIELDS = "MISSING_ORDER_FIELDS";
    public static final String CREDENTIAL_DECRYPT_FAILED = "CREDENTIAL_DECRYPT_FAILED";
    public static final String CREDENTIAL_RECORD_CORRUPT = "CREDENTIAL_RECORD_CORRUPT";
    public static final String CREDENTIAL_AUTH_MODE_UNKNOWN = "CREDENTIAL_AUTH_MODE_UNKNOWN";
    public static final String CREDENTIAL_SOURCE_MISMATCH = "CREDENTIAL_SOURCE_MISMATCH";
    public static final String USER_CONFIGURATION_MISMATCH = "USER_CONFIGURATION_MISMATCH";
    public static final String PLACEHOLDER_CREDENTIALS_DETECTED = "PLACEHOLDER_CREDENTIALS_DETECTED";
    public static final String BINANCE_RATE_LIMIT = "BINANCE_RATE_LIMIT";
    public static final String BINANCE_REJECTED = "BINANCE_REJECTED";
    public static final String ENTRY_FILL_UNRESOLVED = "ENTRY_FILL_UNRESOLVED";
    public static final String PROTECTION_ORDER_INVALID = "PROTECTION_ORDER_INVALID";
    public static final String LIVE_EXECUTION_FAILED = "LIVE_EXECUTION_FAILED";
    public static final String UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT";

    private static final Map<String, Integer> PRIORITIES = Map.ofEntries(
            Map.entry(LIVE_EXECUTION_DISABLED, 10),
            Map.entry(RUNTIME_PERMISSION_DISABLED, 15),
            Map.entry(BOT_READ_ONLY, 20),
            Map.entry(LOCAL_MUTATION_BLOCKED, 30),
            Map.entry(RECOMMENDATION_STALE, 40),
            Map.entry(DUPLICATE_SUBMIT_BLOCKED, 50),
            Map.entry(PLACEABILITY_FAILED, 60),
            Map.entry(EXCHANGE_FILTER_INVALID, 70),
            Map.entry(MISSING_ORDER_FIELDS, 80),
            Map.entry(CREDENTIAL_DECRYPT_FAILED, 85),
            Map.entry(CREDENTIAL_RECORD_CORRUPT, 86),
            Map.entry(CREDENTIAL_AUTH_MODE_UNKNOWN, 87),
            Map.entry(CREDENTIAL_SOURCE_MISMATCH, 88),
            Map.entry(USER_CONFIGURATION_MISMATCH, 89),
            Map.entry(BINANCE_ENDPOINT_MISCONFIGURED, 90),
            Map.entry(PLACEHOLDER_CREDENTIALS_DETECTED, 95),
            Map.entry(BINANCE_TIMESTAMP_INVALID, 100),
            Map.entry(BINANCE_SIGNING_FAILED, 110),
            Map.entry(BINANCE_IP_NOT_ALLOWED, 120),
            Map.entry(BINANCE_FUTURES_PERMISSION_MISSING, 130),
            Map.entry(BINANCE_AUTH_INVALID, 140),
            Map.entry(BINANCE_NETWORK, 150),
            Map.entry(BINANCE_RATE_LIMIT, 160),
            Map.entry(BINANCE_REJECTED, 170),
            Map.entry(ENTRY_FILL_UNRESOLVED, 175),
            Map.entry(PROTECTION_ORDER_INVALID, 176),
            Map.entry(UPSTREAM_TIMEOUT, 180),
            Map.entry(LIVE_EXECUTION_FAILED, 190));

    private LiveTradingBlockerCodes() {
    }

    public static int priority(String code) {
        if (code == null) {
            return Integer.MAX_VALUE;
        }
        return PRIORITIES.getOrDefault(code, Integer.MAX_VALUE - 1);
    }
}
