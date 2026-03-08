package com.tradebot.controller;

import com.tradebot.dto.BinanceCredentialRequestDTO;
import com.tradebot.dto.BinanceCredentialResponseDTO;
import com.tradebot.dto.LiveTradeBlockedReasonDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.operator.RequiresPermission;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.BinanceCredentialException;
import com.tradebot.service.BinanceCredentialService;
import com.tradebot.service.LiveTradingBinanceDiagnosticsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/binance/credentials")
public class BinanceCredentialController {

    private static final Logger log = LoggerFactory.getLogger(BinanceCredentialController.class);

    private final BinanceCredentialService credentialService;
    private final LiveTradingBinanceDiagnosticsService diagnosticsService;
    private final LocalMutationGuard mutationGuard;

    public BinanceCredentialController(BinanceCredentialService credentialService,
            LiveTradingBinanceDiagnosticsService diagnosticsService,
            LocalMutationGuard mutationGuard) {
        this.credentialService = credentialService;
        this.diagnosticsService = diagnosticsService;
        this.mutationGuard = mutationGuard;
    }

    @GetMapping("/status")
    public ResponseEntity<BinanceCredentialResponseDTO> getStatus() {
        return ResponseEntity.ok(credentialService.getStatus());
    }

    @PostMapping
    @RequiresPermission("settings.update")
    public ResponseEntity<BinanceCredentialResponseDTO> saveCredentials(
            @Valid @RequestBody BinanceCredentialRequestDTO request,
            HttpServletRequest servletRequest) {

        mutationGuard.assertLocal(servletRequest);

        BinanceCredentialResponseDTO response = credentialService.saveCredentials(request);
        response.setMessage("Binance credentials saved successfully.");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/test")
    @RequiresPermission("settings.update")
    public ResponseEntity<BinanceCredentialResponseDTO> testCredentials(
            @Valid @RequestBody(required = false) BinanceCredentialRequestDTO request,
            HttpServletRequest servletRequest) {

        mutationGuard.assertLocal(servletRequest);

        BinanceCredentialResponseDTO response = new BinanceCredentialResponseDTO();
        response.setEndpointFamily("BINANCE_FUTURES");

        BinanceCredentialService.ResolvedCredential resolved;
        try {
            resolved = credentialService.resolveCredentialForTest(request);
            response.setAuthMode(resolved.authMode());
            response.setCredentialSource(resolved.source());
        } catch (BinanceCredentialException ex) {
            log.warn("Credential test aborted failureCode={} source={} authMode={}",
                    ex.getFailureCode(), ex.getCredentialSource(), ex.getAuthMode());
            response.setStatus("FAILED");
            response.setCredentialSource(ex.getCredentialSource());
            response.setAuthMode(ex.getAuthMode());
            response.setFailureCode(ex.getFailureCode());
            response.setFailureMessage(ex.getMessage());
            response.setExecutableForLiveFutures(false);
            return ResponseEntity.ok(response);
        }

        try {
            LiveTradingPreflightDTO.Binance diagnostics = diagnosticsService.evaluateResolvedCredential(resolved, "BTCUSDT");
            response.setCredentialSource(diagnostics.getCredentialSource());
            response.setAuthMode(diagnostics.getAuthMode());
            response.setEndpointFamily(diagnostics.getEndpointFamily());
            if (Boolean.TRUE.equals(diagnostics.getAuthValid())) {
                response.setStatus("SUCCESS");
                response.setExecutableForLiveFutures(true);
                response.setMessage("Futures account verified. You can place this order now.");
                log.info("Credential test SUCCESS authMode={} source={} endpointFamily={}",
                        resolved.authMode(), resolved.source(), diagnostics.getEndpointFamily());
            } else {
                response.setStatus("FAILED");
                response.setExecutableForLiveFutures(false);
                response.setFailureCode(diagnostics.getBlockerCode());
                response.setFailureMessage(diagnostics.getBlockerMessage());
                response.setSafeDetails(buildSafeDetails(diagnostics));
                log.warn("Credential test FAILED authMode={} source={} failureCode={}",
                        resolved.authMode(), resolved.source(), diagnostics.getBlockerCode());
            }
        } catch (BinanceCredentialException ex) {
            log.warn("Credential test FAILED before network call failureCode={} source={} authMode={}",
                    ex.getFailureCode(), ex.getCredentialSource(), ex.getAuthMode());
            response.setStatus("FAILED");
            response.setCredentialSource(ex.getCredentialSource());
            response.setAuthMode(ex.getAuthMode());
            response.setFailureCode(ex.getFailureCode());
            response.setFailureMessage(ex.getMessage());
            response.setExecutableForLiveFutures(false);
            response.setSafeDetails(buildSafeDetails(new LiveTradeBlockedReasonDTO(
                    ex.getFailureCode(),
                    ex.getMessage(),
                    "credentials",
                    java.util.Map.of(
                            "credentialSource", ex.getCredentialSource(),
                            "authMode", ex.getAuthMode()))));
        } catch (IllegalStateException ex) {
            LiveTradeBlockedReasonDTO failure = diagnosticsService.classifyExecutionFailure(ex);
            log.warn("Credential test FAILED before network call failureCode={} cause={}",
                    failure.getCode(), ex.getMessage());
            response.setStatus("FAILED");
            response.setFailureCode(failure.getCode());
            response.setFailureMessage(failure.getMessage());
            response.setExecutableForLiveFutures(false);
            response.setSafeDetails(buildSafeDetails(failure));
        } catch (Exception ex) {
            log.warn("Credential test FAILED with unexpected exception cause={}", ex.getMessage());
            response.setStatus("FAILED");
            response.setFailureCode("NETWORK_FAILURE");
            response.setFailureMessage("Network or timeout error: " + ex.getMessage());
            response.setExecutableForLiveFutures(false);
        }

        return ResponseEntity.ok(response);
    }

    private String buildSafeDetails(LiveTradingPreflightDTO.Binance diagnostics) {
        if (diagnostics == null || diagnostics.getEndpointResults() == null) {
            return null;
        }
        return diagnostics.getEndpointResults().stream()
                .filter(result -> !result.isSuccess() && result.getBlockerCode() != null)
                .findFirst()
                .map(result -> {
                    if (result.getBinanceCode() != null) {
                        return "endpoint=" + result.getEndpoint()
                                + " code=" + result.getBinanceCode()
                                + (result.getBinanceMessage() != null ? " msg=" + result.getBinanceMessage() : "");
                    }
                    return "endpoint=" + result.getEndpoint();
                })
                .orElse(null);
    }

    private String buildSafeDetails(LiveTradeBlockedReasonDTO failure) {
        if (failure == null || failure.getDetails() == null || failure.getDetails().isEmpty()) {
            return null;
        }
        Object credentialSource = failure.getDetails().get("credentialSource");
        Object authMode = failure.getDetails().get("authMode");
        if (credentialSource == null && authMode == null) {
            return null;
        }
        return "source=" + credentialSource + " authMode=" + authMode;
    }
}
