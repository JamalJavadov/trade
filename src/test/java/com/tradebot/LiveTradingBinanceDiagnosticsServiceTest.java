package com.tradebot;

import com.tradebot.client.BinanceClient;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.service.BinanceCredentialService;
import com.tradebot.service.LiveTradingBinanceDiagnosticsService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveTradingBinanceDiagnosticsServiceTest {

    @Test
    void evaluateUsesSharedFuturesOnlyVerifier() {
        BinanceClient binanceClient = mock(BinanceClient.class);
        BinanceCredentialService credentialService = mock(BinanceCredentialService.class);

        when(credentialService.inspectActiveCredential()).thenReturn(
                new BinanceCredentialService.CredentialStatusSnapshot(
                        BinanceCredentialService.STATUS_CONFIGURED,
                        BinanceCredentialService.SOURCE_SECURE_UI_SAVED,
                        BinanceCredentialService.AUTH_MODE_ASYMMETRIC_KEYPAIR,
                        null,
                        null,
                        null));
        when(credentialService.resolveActiveCredential()).thenReturn(
                new BinanceCredentialService.ResolvedCredential(
                        "api-key",
                        "private-key",
                        BinanceCredentialService.AUTH_MODE_ASYMMETRIC_KEYPAIR,
                        BinanceCredentialService.SOURCE_SECURE_UI_SAVED));
        when(binanceClient.getFuturesBaseUrl()).thenReturn("https://fapi.binance.com");
        when(binanceClient.getSpotBaseUrl()).thenReturn("https://api.binance.com");
        when(binanceClient.getSignedRecvWindowMs()).thenReturn(5_000L);
        when(binanceClient.getFuturesServerTime()).thenReturn(1_762_000_000_100L);
        when(binanceClient.verifyFuturesAccount(
                "api-key",
                "private-key",
                BinanceCredentialService.AUTH_MODE_ASYMMETRIC_KEYPAIR,
                BinanceCredentialService.SOURCE_SECURE_UI_SAVED)).thenReturn(Map.of("canTrade", true));
        when(binanceClient.verifyFuturesAccountConfig(
                "api-key",
                "private-key",
                BinanceCredentialService.AUTH_MODE_ASYMMETRIC_KEYPAIR,
                BinanceCredentialService.SOURCE_SECURE_UI_SAVED)).thenReturn(Map.of("multiAssetsMargin", false));

        LiveTradingBinanceDiagnosticsService service = new LiveTradingBinanceDiagnosticsService(
                binanceClient,
                credentialService);

        LiveTradingPreflightDTO.Binance diagnostics = service.evaluate("BTCUSDT");

        assertTrue(diagnostics.isCredentialsPresent());
        assertEquals("SECURE_UI_SAVED", diagnostics.getCredentialSource());
        assertEquals("ASYMMETRIC_KEYPAIR", diagnostics.getAuthMode());
        assertTrue(Boolean.TRUE.equals(diagnostics.getAuthValid()));
        assertTrue(Boolean.TRUE.equals(diagnostics.getAccountInfoReadOk()));
        assertTrue(Boolean.TRUE.equals(diagnostics.getAccountConfigReadOk()));
        assertTrue(Boolean.TRUE.equals(diagnostics.getFuturesPermissionOk()));
        assertNull(diagnostics.getBlockerCode());
        assertEquals(3, diagnostics.getEndpointResults().size());
        assertTrue(diagnostics.getEndpointResults().stream()
                .anyMatch(result -> "/fapi/v2/account".equals(result.getEndpoint())));
        assertTrue(diagnostics.getEndpointResults().stream()
                .anyMatch(result -> "/fapi/v1/accountConfig".equals(result.getEndpoint())));
        assertFalse(diagnostics.getEndpointResults().stream()
                .anyMatch(result -> "/api/v3/account".equals(result.getEndpoint())));
    }
}
