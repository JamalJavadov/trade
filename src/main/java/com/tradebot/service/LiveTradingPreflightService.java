package com.tradebot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.config.AppProperties;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.BinanceOrderFieldsDTO;
import com.tradebot.dto.LiveTradeBlockedReasonDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.dto.RecommendationPlaceabilityDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.OrderFields;
import com.tradebot.entity.Recommendation;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.security.LocalMutationGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
            LiveTradeExecutionState.REQUESTED,
            LiveTradeExecutionState.SUBMITTING,
            LiveTradeExecutionState.ENTRY_SUBMITTED,
            LiveTradeExecutionState.ENTRY_PARTIALLY_FILLED,
            LiveTradeExecutionState.ENTRY_FILLED,
            LiveTradeExecutionState.PROTECTION_SUBMITTING,
            LiveTradeExecutionState.PROTECTION_SUBMITTED,
            LiveTradeExecutionState.PROTECTION_ACTIVE,
            LiveTradeExecutionState.OPEN,
            LiveTradeExecutionState.RECONCILING,
            LiveTradeExecutionState.PENDING_RECONCILE,
            LiveTradeExecutionState.PROTECTION_FAILED,
            LiveTradeExecutionState.EMERGENCY_CLOSE_SUBMITTED);

    private final RecommendationRepository recommendationRepository;
    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final RecommendationPlaceabilityService recommendationPlaceabilityService;
    private final BinanceClient binanceClient;
    private final LiveTradingBinanceDiagnosticsService liveTradingBinanceDiagnosticsService;
    private final OperatorPermissionService operatorPermissionService;
    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public LiveTradingPreflightDTO evaluate(UUID recommendationId) {
        return evaluate(recommendationId, null, null);
    }

    public LiveTradingPreflightDTO evaluate(UUID recommendationId, UUID ignoreExecutionId) {
        return evaluate(recommendationId, ignoreExecutionId, null);
    }

    public LiveTradingPreflightDTO evaluate(UUID recommendationId,
            UUID ignoreExecutionId,
            LocalMutationGuard.LocalRequestCheck localRequestCheck) {
        Recommendation recommendation = recommendationRepository.findById(recommendationId)
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
        OrderPayloads payloads = parseOrderPayloads(recommendation.getOrderFields());
        if (payloads == null) {
            dto.getExchangeValidation().setValid(false);
            dto.getExchangeValidation().getFailures().add("Recommendation order payloads are missing or invalid.");
            addBlocked(dto,
                    LiveTradingBlockerCodes.MISSING_ORDER_FIELDS,
                    "Recommendation order payloads are missing or invalid.",
                    "exchangeValidation",
                    Map.of());
            return;
        }

        Optional<com.tradebot.dto.BinanceExchangeInfoResponse.SymbolInfo> symbolInfoOptional = binanceClient
                .getSymbolInfo(recommendation.getSymbol());
        if (symbolInfoOptional.isEmpty()) {
            addExchangeFailure(dto, "Symbol is not present in Binance Futures exchange info.");
            finalizeExchangeFailure(dto);
            return;
        }

        com.tradebot.dto.BinanceExchangeInfoResponse.SymbolInfo symbolInfo = symbolInfoOptional.get();
        if (!"TRADING".equalsIgnoreCase(symbolInfo.getStatus())) {
            addExchangeFailure(dto, "Symbol is not currently tradable on Binance Futures.");
        }
        if (!"PERPETUAL".equalsIgnoreCase(symbolInfo.getContractType())
                || !"USDT".equalsIgnoreCase(symbolInfo.getQuoteAsset())) {
            addExchangeFailure(dto, "Symbol is not a USDT perpetual futures contract.");
        }

        BigDecimal markPrice = toBigDecimal(
                dto.getPlaceability() != null ? dto.getPlaceability().getMarkPrice() : null);
        if (markPrice == null) {
            try {
                markPrice = binanceClient.getMarkPrice(recommendation.getSymbol());
            } catch (Exception ex) {
                addExchangeFailure(dto, "Live mark price is unavailable for execution preflight.");
            }
        }

        BigDecimal tickSize = nonZero(symbolInfo.getTickSize());
        BigDecimal stepSize = nonZero(symbolInfo.getMarketStepSize());
        if (stepSize == null) {
            stepSize = nonZero(symbolInfo.getStepSize());
        }
        BigDecimal minQty = nonZero(symbolInfo.getMarketMinQty());
        if (minQty == null) {
            minQty = nonZero(symbolInfo.getMinQty());
        }
        BigDecimal minNotional = nonZero(symbolInfo.getMinNotional());

        dto.getExchangeValidation().setMarkPrice(markPrice);
        dto.getExchangeValidation().setTickSize(tickSize);
        dto.getExchangeValidation().setStepSize(stepSize);
        dto.getExchangeValidation().setMinQty(minQty);
        dto.getExchangeValidation().setMinNotional(minNotional);
        dto.getExchangeValidation().setQuantity(payloads.entry().getQuantity());
        dto.getExchangeValidation().setSlStopPrice(payloads.sl().getStopPrice());
        dto.getExchangeValidation().setTpStopPrice(payloads.tp().getStopPrice());
        dto.getExchangeValidation().setLeverage(recommendation.getOrderFields() != null
                ? recommendation.getOrderFields().getLeverageRecommendation()
                : appProperties.getLeverage());
        dto.getExchangeValidation().setMarginMode(recommendation.getOrderFields() != null
                ? recommendation.getOrderFields().getMarginMode()
                : null);
        dto.getExchangeValidation().setPositionMode(recommendation.getOrderFields() != null
                ? recommendation.getOrderFields().getPositionMode()
                : null);

        if (tickSize == null) {
            addExchangeFailure(dto, "Binance price filter tick size is unavailable.");
        }
        if (stepSize == null) {
            addExchangeFailure(dto, "Binance market lot size step is unavailable.");
        }
        if (minQty == null) {
            addExchangeFailure(dto, "Binance minimum quantity rule is unavailable.");
        }
        if (payloads.entry().getQuantity() == null || payloads.entry().getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            addExchangeFailure(dto, "Entry quantity is missing or invalid.");
        }
        if (payloads.sl().getStopPrice() == null || payloads.tp().getStopPrice() == null) {
            addExchangeFailure(dto, "Protection order stop prices are missing.");
        }
        if (!"ISOLATED".equalsIgnoreCase(dto.getExchangeValidation().getMarginMode())) {
            addExchangeFailure(dto, "Execution requires isolated margin mode.");
        }
        if (!"ONE_WAY".equalsIgnoreCase(dto.getExchangeValidation().getPositionMode())) {
            addExchangeFailure(dto, "Execution requires one-way position mode.");
        }

        if (tickSize != null && payloads.sl().getStopPrice() != null
                && !alignsToIncrement(payloads.sl().getStopPrice(), tickSize)) {
            addExchangeFailure(dto, "Stop-loss price does not match Binance tick size.");
        }
        if (tickSize != null && payloads.tp().getStopPrice() != null
                && !alignsToIncrement(payloads.tp().getStopPrice(), tickSize)) {
            addExchangeFailure(dto, "Take-profit price does not match Binance tick size.");
        }
        if (stepSize != null && payloads.entry().getQuantity() != null
                && !alignsToIncrement(payloads.entry().getQuantity(), stepSize)) {
            addExchangeFailure(dto, "Entry quantity does not match Binance market step size.");
        }
        if (minQty != null && payloads.entry().getQuantity() != null
                && payloads.entry().getQuantity().compareTo(minQty) < 0) {
            addExchangeFailure(dto, "Entry quantity is below Binance minimum quantity.");
        }

        if (markPrice != null && payloads.entry().getQuantity() != null) {
            BigDecimal notional = markPrice.multiply(payloads.entry().getQuantity());
            dto.getExchangeValidation().setEntryNotionalUsdt(notional);
            if (minNotional != null && notional.compareTo(minNotional) < 0) {
                addExchangeFailure(dto, "Entry notional is below Binance minimum notional.");
            }
        }

        finalizeExchangeFailure(dto);
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

    private void finalizeExchangeFailure(LiveTradingPreflightDTO dto) {
        if (!dto.getExchangeValidation().getFailures().isEmpty()) {
            dto.getExchangeValidation().setValid(false);
            addBlocked(dto,
                    LiveTradingBlockerCodes.EXCHANGE_FILTER_INVALID,
                    dto.getExchangeValidation().getFailures().get(0),
                    "exchangeValidation",
                    detailsOf("failures", dto.getExchangeValidation().getFailures()));
        }
    }

    private void addExchangeFailure(LiveTradingPreflightDTO dto, String message) {
        dto.getExchangeValidation().setValid(false);
        dto.getExchangeValidation().getFailures().add(message);
    }

    private OrderPayloads parseOrderPayloads(OrderFields orderFields) {
        if (orderFields == null) {
            return null;
        }
        try {
            BinanceOrderFieldsDTO entry = objectMapper.readValue(orderFields.getEntryOrderJson(),
                    BinanceOrderFieldsDTO.class);
            BinanceOrderFieldsDTO sl = objectMapper.readValue(orderFields.getSlOrderJson(),
                    BinanceOrderFieldsDTO.class);
            BinanceOrderFieldsDTO tp = objectMapper.readValue(orderFields.getTpOrderJson(),
                    BinanceOrderFieldsDTO.class);
            return new OrderPayloads(entry, sl, tp);
        } catch (Exception ex) {
            return null;
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

    private boolean alignsToIncrement(BigDecimal value, BigDecimal increment) {
        if (value == null || increment == null || increment.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal normalized = value.divide(increment, 0, RoundingMode.DOWN).multiply(increment);
        return normalized.compareTo(value) == 0;
    }

    private BigDecimal nonZero(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return value;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
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

    private record OrderPayloads(BinanceOrderFieldsDTO entry, BinanceOrderFieldsDTO sl, BinanceOrderFieldsDTO tp) {
    }
}
