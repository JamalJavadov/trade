package com.tradebot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.dto.LiveTradeBlockedReasonDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.dto.RecommendationPlaceabilityDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.Recommendation;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.security.LocalMutationGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LiveTradingPreflightService {

    private static final long RECOMMENDATION_STALE_THRESHOLD_SECONDS = Duration.of(15, ChronoUnit.MINUTES).toSeconds();
    private static final List<LiveTradeExecutionState> DUPLICATE_BLOCK_STATES = List.of(
            LiveTradeExecutionState.CREATED,
            LiveTradeExecutionState.PREFLIGHT_VALIDATING,
            LiveTradeExecutionState.ENTRY_SUBMITTING,
            LiveTradeExecutionState.ENTRY_SUBMITTED,
            LiveTradeExecutionState.ENTRY_FILLED,
            LiveTradeExecutionState.PROTECTION_SUBMITTING,
            LiveTradeExecutionState.PROTECTION_ACTIVE,
            LiveTradeExecutionState.ACTIVE,
            LiveTradeExecutionState.CLOSING,
            LiveTradeExecutionState.RECONCILING,
            LiveTradeExecutionState.FAILED);

    private final RecommendationRepository recommendationRepository;
    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final RecommendationPlaceabilityService recommendationPlaceabilityService;
    private final LiveTradingBinanceDiagnosticsService liveTradingBinanceDiagnosticsService;
    private final OperatorPermissionService operatorPermissionService;
    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final ExchangeExecutionPreflightService exchangeExecutionPreflightService;

    public LiveTradingPreflightDTO evaluate(UUID recommendationId) {
        return evaluate(recommendationId, null, null);
    }

    public LiveTradingPreflightDTO evaluate(UUID recommendationId, UUID ignoreExecutionId) {
        return evaluate(recommendationId, ignoreExecutionId, null);
    }

    public LiveTradingPreflightDTO evaluate(UUID recommendationId,
            UUID ignoreExecutionId,
            LocalMutationGuard.LocalRequestCheck localRequestCheck) {
        Recommendation recommendation = recommendationRepository.findDetailedById(recommendationId)
                .orElseThrow(() -> new NoSuchElementException("Recommendation not found: " + recommendationId));

        LiveTradingPreflightDTO dto = new LiveTradingPreflightDTO();
        dto.setRecommendationId(recommendation.getId());
        dto.setSymbol(recommendation.getSymbol());
        dto.setSide(recommendation.getSide());
        dto.setCheckedAt(Instant.now());

        populateRuntime(dto, recommendation, ignoreExecutionId);
        populateLocalRequest(dto, localRequestCheck);
        populatePlaceability(dto, recommendationId);
        populateExchangeValidation(dto, recommendation);
        populateBinanceDiagnostics(dto, recommendation.getSymbol());
        sortAndFinalize(dto);
        return dto;
    }

    public LiveTradingPreflightDTO evaluateHealth(String symbol,
            LocalMutationGuard.LocalRequestCheck localRequestCheck) {
        LiveTradingPreflightDTO dto = new LiveTradingPreflightDTO();
        dto.setCheckedAt(Instant.now());
        dto.setSymbol(symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase());
        populateRuntime(dto, null, null);
        populateLocalRequest(dto, localRequestCheck);
        populateBinanceDiagnostics(dto, dto.getSymbol());
        sortAndFinalize(dto);
        return dto;
    }

    private void populateRuntime(LiveTradingPreflightDTO dto, Recommendation recommendation, UUID ignoreExecutionId) {
        boolean liveExecutionEnabled = operatorPermissionService.isPermissionEnabled("live.execution.enabled");
        boolean readOnly = controlCenterSettingsProvider.isLiveExecutionReadOnly();

        dto.setExecutionEnabled(liveExecutionEnabled);
        dto.getRuntime().setLiveExecutionEnabled(liveExecutionEnabled);
        dto.getRuntime().setReadOnly(readOnly);
        dto.getRuntime().setTradingEnabled(!readOnly);
        dto.getRuntime().setStaleThresholdSeconds(RECOMMENDATION_STALE_THRESHOLD_SECONDS);
        dto.getRuntime().setRuntimeReady(liveExecutionEnabled && !readOnly);

        if (!liveExecutionEnabled) {
            addBlocked(dto,
                    LiveTradingBlockerCodes.LIVE_EXECUTION_DISABLED,
                    "Manual Binance execution is disabled in Control Center.",
                    "runtime",
                    detailsOf("permissionKey", "live.execution.enabled"));
        }

        if (readOnly) {
            addBlocked(dto,
                    LiveTradingBlockerCodes.BOT_READ_ONLY,
                    "Trading is disabled. Bot is in READ-ONLY mode.",
                    "runtime",
                    detailsOf("configPath", "liveExecution.readOnly"));
        }

        if (recommendation != null) {
            LiveTradeExecution activeExecution = liveTradeExecutionRepository
                    .findFirstByRecommendation_IdAndExecutionStateInOrderByCreatedAtDesc(
                            recommendation.getId(),
                            DUPLICATE_BLOCK_STATES)
                    .orElse(null);
            if (activeExecution != null && !activeExecution.getId().equals(ignoreExecutionId)) {
                dto.getRuntime().setDuplicateSubmitBlocked(true);
                addBlocked(dto,
                        LiveTradingBlockerCodes.DUPLICATE_SUBMIT_BLOCKED,
                        "Another live execution attempt for this recommendation is still active.",
                        "runtime",
                        detailsOf(
                                "executionId", activeExecution.getId(),
                                "executionState", activeExecution.getExecutionState(),
                                "clientRequestId", activeExecution.getClientRequestId(),
                                "createdAt", activeExecution.getCreatedAt()));
            }
        }

        if (recommendation != null && recommendation.getCreatedAt() != null) {
            long ageSeconds = Math.max(0L, Duration.between(recommendation.getCreatedAt(), Instant.now()).getSeconds());
            dto.getRuntime().setRecommendationAgeSeconds(ageSeconds);
            boolean stale = ageSeconds > RECOMMENDATION_STALE_THRESHOLD_SECONDS;
            dto.getRuntime().setRecommendationStale(stale);
            if (stale) {
                addBlocked(dto,
                        LiveTradingBlockerCodes.RECOMMENDATION_STALE,
                        "Recommendation is stale for live execution. Refresh with a newer recommendation before submitting.",
                        "runtime",
                        detailsOf(
                                "recommendationCreatedAt", recommendation.getCreatedAt(),
                                "recommendationAgeSeconds", ageSeconds,
                                "staleThresholdSeconds", RECOMMENDATION_STALE_THRESHOLD_SECONDS));
            }
        }
    }

    private void populateLocalRequest(LiveTradingPreflightDTO dto,
            LocalMutationGuard.LocalRequestCheck localRequestCheck) {
        if (localRequestCheck == null) {
            dto.getLocalRequest().setAllowed(true);
            return;
        }
        dto.getLocalRequest().setAllowed(localRequestCheck.allowed());
        dto.getLocalRequest().setRemoteAddress(localRequestCheck.remoteAddress());
        dto.getLocalRequest().setForwardedFor(localRequestCheck.forwardedFor());
        dto.getLocalRequest().setOrigin(localRequestCheck.origin());
        dto.getLocalRequest().setFailureReason(localRequestCheck.failureReason());
        if (!localRequestCheck.allowed()) {
            addBlocked(dto,
                    LiveTradingBlockerCodes.LOCAL_MUTATION_BLOCKED,
                    "Live execution is local-only. Open the UI from localhost and submit from the same workstation.",
                    "localRequest",
                    detailsOf(
                            "remoteAddress", localRequestCheck.remoteAddress(),
                            "forwardedFor", localRequestCheck.forwardedFor(),
                            "origin", localRequestCheck.origin(),
                            "failureReason", localRequestCheck.failureReason()));
        }
    }

    private void populatePlaceability(LiveTradingPreflightDTO dto, UUID recommendationId) {
        RecommendationPlaceabilityDTO placeability = recommendationPlaceabilityService.evaluate(recommendationId);
        dto.setPlaceability(placeability);
        dto.setPlaceabilityOk(placeability != null && placeability.isManualPlacementAllowed());
        if (placeability != null && !placeability.isManualPlacementAllowed()) {
            addBlocked(dto,
                    LiveTradingBlockerCodes.PLACEABILITY_FAILED,
                    "Recommendation is not placeable now: " + safeText(placeability.getReasonText(), "unknown reason"),
                    "placeability",
                    detailsOf(
                            "reasonCode", placeability.getReasonCode(),
                            "reasonText", placeability.getReasonText()));
        }
    }

    private void populateExchangeValidation(LiveTradingPreflightDTO dto, Recommendation recommendation) {
        ExchangeExecutionPreflightService.ExchangeExecutionPreflightResult result =
                exchangeExecutionPreflightService.evaluate(recommendation, dto.getPlaceability());
        exchangeExecutionPreflightService.applyTo(dto, result);
        if (!result.valid()) {
            addBlocked(dto,
                    LiveTradingBlockerCodes.EXCHANGE_FILTER_INVALID,
                    result.failures().getFirst(),
                    "exchangeValidation",
                    result.details());
        }
    }

    private void populateBinanceDiagnostics(LiveTradingPreflightDTO dto, String symbol) {
        LiveTradingPreflightDTO.Binance diagnostics = liveTradingBinanceDiagnosticsService.evaluate(symbol);
        dto.setBinance(diagnostics);
        if (diagnostics.getBlockerCode() != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("credentialSource", diagnostics.getCredentialSource());
            details.put("authMode", diagnostics.getAuthMode());
            details.put("endpointFamily", diagnostics.getEndpointFamily());
            details.put("baseUrl", diagnostics.getBaseUrl());
            details.put("recvWindowMs", diagnostics.getRecvWindowMs());
            details.put("timestampSkewMs", diagnostics.getTimestampSkewMs());
            details.put("requestIpHint", diagnostics.getRequestIpHint());
            addBlocked(dto,
                    diagnostics.getBlockerCode(),
                    diagnostics.getBlockerMessage(),
                    "binance",
                    details);
        }
    }

    private void sortAndFinalize(LiveTradingPreflightDTO dto) {
        dto.getBlockedReasons().sort((left, right) -> Integer.compare(
                LiveTradingBlockerCodes.priority(left.getCode()),
                LiveTradingBlockerCodes.priority(right.getCode())));
        boolean executable = dto.getBlockedReasons().isEmpty();
        dto.setExecutable(executable);
        dto.setAllowed(executable);
        finalizeSummary(dto);
    }

    private void finalizeSummary(LiveTradingPreflightDTO dto) {
        LiveTradingPreflightDTO.Summary summary = dto.getSummary();

        Boolean authValid = dto.getBinance().getAuthValid();
        if (Boolean.TRUE.equals(authValid)) {
            summary.setConnectionStatus("CONNECTED");
        } else if (Boolean.FALSE.equals(authValid)) {
            summary.setConnectionStatus("NOT_CONNECTED");
        } else {
            summary.setConnectionStatus("UNKNOWN");
        }

        summary.setExecutableNow(dto.isExecutable());
        summary.setAdvancedDiagnosticsAvailable(!dto.getBlockedReasons().isEmpty());

        if (!dto.getBlockedReasons().isEmpty()) {
            LiveTradeBlockedReasonDTO primary = dto.getBlockedReasons().get(0);
            summary.setPrimaryBlockerCode(primary.getCode());
            summary.setPrimaryBlockerMessage(toOperatorMessage(primary));
        }
    }

    private String toOperatorMessage(LiveTradeBlockedReasonDTO reason) {
        return switch (reason.getCode()) {
            case LiveTradingBlockerCodes.LIVE_EXECUTION_DISABLED ->
                "Trading is disabled in Control Center.";
            case LiveTradingBlockerCodes.BOT_READ_ONLY ->
                "Bot is in read-only mode. Disable read-only in Control Center.";
            case LiveTradingBlockerCodes.LOCAL_MUTATION_BLOCKED ->
                "Live execution requires a local request. Open the UI from localhost.";
            case LiveTradingBlockerCodes.RECOMMENDATION_STALE ->
                "Recommendation is stale. Refresh or start a new scan.";
            case LiveTradingBlockerCodes.DUPLICATE_SUBMIT_BLOCKED ->
                "An in-flight execution for this recommendation is still active. Wait for it to complete.";
            case LiveTradingBlockerCodes.PLACEABILITY_FAILED ->
                "Recommendation is not placeable right now.";
            case LiveTradingBlockerCodes.EXCHANGE_FILTER_INVALID ->
                "Order fields do not pass Binance exchange filters.";
            case LiveTradingBlockerCodes.CREDENTIAL_DECRYPT_FAILED ->
                "Binance credentials could not be decrypted. Re-save credentials in Settings.";
            case LiveTradingBlockerCodes.CREDENTIAL_RECORD_CORRUPT ->
                "Saved Binance credentials are incomplete. Re-save credentials in Settings.";
            case LiveTradingBlockerCodes.CREDENTIAL_AUTH_MODE_UNKNOWN ->
                "Credential auth mode is inconsistent. Update credentials in Settings.";
            case LiveTradingBlockerCodes.CREDENTIAL_SOURCE_MISMATCH ->
                "The selected test credentials do not match the active saved auth mode.";
            case LiveTradingBlockerCodes.USER_CONFIGURATION_MISMATCH ->
                "The saved Binance credentials do not match the selected asymmetric credential type.";
            case LiveTradingBlockerCodes.PLACEHOLDER_CREDENTIALS_DETECTED ->
                "The Binance credentials appear to be placeholder or demo values.";
            case LiveTradingBlockerCodes.BINANCE_AUTH_INVALID ->
                "Binance credentials are invalid.";
            case LiveTradingBlockerCodes.BINANCE_SIGNING_FAILED ->
                "The backend could not sign the Binance request for the selected credential type.";
            case LiveTradingBlockerCodes.BINANCE_IP_NOT_ALLOWED ->
                "This server's IP is not on the Binance API allowlist.";
            case LiveTradingBlockerCodes.BINANCE_FUTURES_PERMISSION_MISSING ->
                "Binance API key is missing Futures trading permission.";
            case LiveTradingBlockerCodes.BINANCE_NETWORK ->
                "Cannot reach Binance API. Check network connectivity.";
            default -> reason.getMessage() != null ? reason.getMessage() : reason.getCode();
        };
    }

    private void addBlocked(LiveTradingPreflightDTO dto,
            String code,
            String message,
            String source,
            Map<String, Object> details) {
        dto.getBlockedReasons().add(new LiveTradeBlockedReasonDTO(code, message, source, details));
    }

    private String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
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
