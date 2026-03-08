package com.tradebot;

import com.tradebot.service.BinanceErrorClassifier;
import com.tradebot.service.LiveTradingBlockerCodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BinanceErrorClassifierTest {

    @Test
    void ambiguousInvalidApiKeyMessageStaysAuthInvalidWithoutIpHint() {
        BinanceErrorClassifier.BinanceErrorDetails details = new BinanceErrorClassifier.BinanceErrorDetails(
                "fapi.binance.com",
                "/fapi/v2/account",
                401,
                -2015,
                "Invalid API-key, IP, or permissions for action.",
                "{\"code\":-2015,\"msg\":\"Invalid API-key, IP, or permissions for action.\"}",
                null);

        assertFalse(BinanceErrorClassifier.isIpNotAllowed(details));
        assertEquals(LiveTradingBlockerCodes.BINANCE_AUTH_INVALID, BinanceErrorClassifier.classify(details));
    }
}
