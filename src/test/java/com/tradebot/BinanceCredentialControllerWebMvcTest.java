package com.tradebot;

import com.tradebot.config.TraceIdFilter;
import com.tradebot.config.WebConfig;
import com.tradebot.controller.BinanceCredentialController;
import com.tradebot.dto.BinanceCredentialResponseDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.operator.OperatorPermissionInterceptor;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.BinanceCredentialException;
import com.tradebot.service.BinanceCredentialService;
import com.tradebot.service.LiveTradingBinanceDiagnosticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BinanceCredentialController.class)
@Import({ GlobalExceptionHandler.class, TraceIdFilter.class, WebConfig.class, OperatorPermissionInterceptor.class })
class BinanceCredentialControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BinanceCredentialService credentialService;

    @MockBean
    private LiveTradingBinanceDiagnosticsService diagnosticsService;

    @MockBean
    private LocalMutationGuard localMutationGuard;

    @MockBean
    private OperatorPermissionService operatorPermissionService;

    @Test
    void statusReturnsBrokenCredentialTruthfully() throws Exception {
        BinanceCredentialResponseDTO response = new BinanceCredentialResponseDTO();
        response.setStatus("BROKEN");
        response.setCredentialSource("SECURE_UI_SAVED");
        response.setAuthMode("ASYMMETRIC_KEYPAIR");
        response.setFailureCode("CREDENTIAL_DECRYPT_FAILED");
        response.setFailureMessage("Saved Binance credentials are unreadable. Please re-save them.");
        when(credentialService.getStatus()).thenReturn(response);

        mockMvc.perform(get("/api/v1/integrations/binance/credentials/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BROKEN"))
                .andExpect(jsonPath("$.credentialSource").value("SECURE_UI_SAVED"))
                .andExpect(jsonPath("$.authMode").value("ASYMMETRIC_KEYPAIR"))
                .andExpect(jsonPath("$.failureCode").value("CREDENTIAL_DECRYPT_FAILED"));
    }

    @Test
    void testEndpointUsesResolvedSourceAndAuthMode() throws Exception {
        when(credentialService.resolveCredentialForTest(isNull()))
                .thenReturn(new BinanceCredentialService.ResolvedCredential(
                        "api-key",
                        "secret-value",
                        "ASYMMETRIC_KEYPAIR",
                        "SECURE_UI_SAVED"));
        LiveTradingPreflightDTO.Binance diagnostics = new LiveTradingPreflightDTO.Binance();
        diagnostics.setCredentialSource("SECURE_UI_SAVED");
        diagnostics.setAuthMode("ASYMMETRIC_KEYPAIR");
        diagnostics.setEndpointFamily("BINANCE_FUTURES");
        diagnostics.setAuthValid(true);
        when(diagnosticsService.evaluateResolvedCredential(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("BTCUSDT")))
                .thenReturn(diagnostics);

        mockMvc.perform(post("/api/v1/integrations/binance/credentials/test")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.credentialSource").value("SECURE_UI_SAVED"))
                .andExpect(jsonPath("$.authMode").value("ASYMMETRIC_KEYPAIR"))
                .andExpect(jsonPath("$.endpointFamily").value("BINANCE_FUTURES"))
                .andExpect(jsonPath("$.executableForLiveFutures").value(true));
    }

    @Test
    void testEndpointPreservesTypedCredentialFailures() throws Exception {
        when(credentialService.resolveCredentialForTest(isNull()))
                .thenThrow(new BinanceCredentialException(
                        "CREDENTIAL_DECRYPT_FAILED",
                        "Saved Binance credentials are unreadable. Please re-save them.",
                        "SECURE_UI_SAVED",
                        "ASYMMETRIC_KEYPAIR"));

        mockMvc.perform(post("/api/v1/integrations/binance/credentials/test")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.credentialSource").value("SECURE_UI_SAVED"))
                .andExpect(jsonPath("$.authMode").value("ASYMMETRIC_KEYPAIR"))
                .andExpect(jsonPath("$.failureCode").value("CREDENTIAL_DECRYPT_FAILED"))
                .andExpect(jsonPath("$.failureMessage")
                        .value("Saved Binance credentials are unreadable. Please re-save them."));
    }
}
