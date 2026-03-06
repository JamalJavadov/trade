package com.tradebot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.config.AppProperties;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.BinanceOrderFieldsDTO;
import com.tradebot.dto.RecommendationPlaceabilityDTO;
import com.tradebot.entity.OrderFields;
import com.tradebot.entity.Recommendation;
import com.tradebot.entity.SymbolEvaluation;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.SymbolEvaluationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecommendationPlaceabilityService {

    private final RecommendationRepository recommendationRepository;
    private final SymbolEvaluationRepository symbolEvaluationRepository;
    private final BinanceClient binanceClient;
    private final AppProperties appProperties;
    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final ObjectMapper objectMapper;

    public RecommendationPlaceabilityDTO evaluate(UUID recommendationId) {
        RecommendationPlaceabilityDTO dto = baseResponse(recommendationId);

        Optional<Recommendation> maybeRec = recommendationRepository.findById(recommendationId);
        if (maybeRec.isEmpty()) {
            return notPlaceable(dto, "NOT_FOUND", "Recommendation not found.");
        }

        Recommendation rec = maybeRec.get();
        dto.setSymbol(rec.getSymbol());
        dto.setSide(RecommendationPlaceabilityMath.isLongSide(rec.getSide()) ? "LONG" : "SHORT");
        dto.getRules().setInequalityRule(RecommendationPlaceabilityMath.isLongSide(rec.getSide())
                ? "TP > MARK > SL"
                : "SL > MARK > TP");
        dto.setRuleText(dto.getRules().getInequalityRule());
        dto.setRequiredInequality(RecommendationPlaceabilityMath.isLongSide(rec.getSide())
                ? "LONG requires TP >= MARK + 1 tick and SL <= MARK - 1 tick."
                : "SHORT requires SL >= MARK + 1 tick and TP <= MARK - 1 tick.");

        PriceLevels levels = resolveLevels(rec);
        if (levels == null || levels.tp1() == null || levels.sl() == null) {
            return notPlaceable(dto, "MISSING_DATA", "Recommendation does not contain TP/SL data.");
        }

        dto.setTpRaw(levels.tp1());
        dto.setSlRaw(levels.sl());
        dto.setTpDisplay(levels.tp1());
        dto.setSlDisplay(levels.sl());
        dto.getComputed().setTp1(levels.tp1());
        dto.getComputed().setSl(levels.sl());

        BigDecimal mark;
        try {
            mark = binanceClient.getMarkPrice(rec.getSymbol());
            dto.setMarkPrice(mark);
        } catch (WebClientResponseException ex) {
            return handleWebClientResponseException(dto, ex);
        } catch (WebClientRequestException ex) {
            return notPlaceable(dto, "BINANCE_NETWORK", "Binance MARK price request timed out or failed.");
        } catch (Exception ex) {
            return notPlaceable(dto, "BINANCE_UNAVAILABLE", "Binance MARK price is currently unavailable.");
        }

        BigDecimal tick;
        try {
            tick = binanceClient.getTickSize(rec.getSymbol());
            dto.setTickSize(tick);
        } catch (IllegalArgumentException ex) {
            return notPlaceable(dto, "MISSING_TICKSIZE", "Tick size is unavailable for this symbol.");
        } catch (WebClientResponseException ex) {
            return handleWebClientResponseException(dto, ex);
        } catch (WebClientRequestException ex) {
            return notPlaceable(dto, "BINANCE_NETWORK", "Binance exchange info request timed out or failed.");
        } catch (Exception ex) {
            return notPlaceable(dto, "BINANCE_UNAVAILABLE", "Binance exchange info is currently unavailable.");
        }

        if (tick == null || tick.compareTo(BigDecimal.ZERO) <= 0) {
            return notPlaceable(dto, "MISSING_TICKSIZE", "Tick size is unavailable for this symbol.");
        }

        RecommendationPlaceabilityMath.PlaceabilityResult result = RecommendationPlaceabilityMath.evaluate(
                rec.getSide(),
                mark,
                tick,
                levels.tp1(),
                levels.sl(),
                controlCenterSettingsProvider.getConfigSnapshot().getStrategyLocks().getMinRr());

        dto.getChecks().setTpOk(result.tpOk());
        dto.getChecks().setSlOk(result.slOk());
        dto.getChecks().setRrOk(result.rrOk());
        dto.getComputed().setRrToTp1(result.rrToTp1());
        dto.getComputed().setSuggestedTp1Adjusted(result.suggestedTp1Adjusted());
        dto.getComputed().setSuggestedSlAdjusted(result.suggestedSlAdjusted());
        dto.setLiveRrToTp1(result.rrToTp1());
        dto.setMinRrRequired(result.minRrRequired());
        dto.setViolations(result.violations());
        dto.setAdjustments(result.adjustments());

        if (result.placeable()) {
            dto.setPlaceable(true);
            dto.setManualPlacementAllowed(true);
            dto.setReasonCode("OK");
            dto.setReasonText("Trade is placeable against LIVE MARK.");
            return dto;
        }

        dto.setPlaceable(false);
        dto.setManualPlacementAllowed(false);
        if (!result.tpOk() || !result.slOk()) {
            dto.setReasonCode("PRICE_NEEDS_TICK_GAP");
            dto.setReasonText("TP/SL no longer satisfy the required MARK +/- tick gap.");
            return dto;
        }
        if (!result.rrOk()) {
            dto.setReasonCode("RR_BELOW_2");
            dto.setReasonText("Live RR to TP1 is below the minimum required ratio.");
            return dto;
        }

        dto.setReasonCode("PRICE_MOVED");
        dto.setReasonText("Market price moved and setup is no longer placeable.");
        return dto;
    }

    private RecommendationPlaceabilityDTO handleWebClientResponseException(RecommendationPlaceabilityDTO dto,
            WebClientResponseException ex) {
        int status = ex.getStatusCode().value();
        if (status == 429) {
            String retryAfter = ex.getHeaders().getFirst("Retry-After");
            String text = retryAfter != null && !retryAfter.isBlank()
                    ? "Binance rate limit exceeded. Retry after " + retryAfter + " seconds."
                    : "Binance rate limit exceeded. Retry later.";
            return notPlaceable(dto, "BINANCE_RATE_LIMIT", text);
        }
        if (status == 401 || status == 403) {
            return notPlaceable(dto, "BINANCE_AUTH", "Binance authentication/permission failed.");
        }
        return notPlaceable(dto, "BINANCE_UNAVAILABLE", "Binance request failed with status " + status + ".");
    }

    private PriceLevels resolveLevels(Recommendation rec) {
        PriceLevels fromOrderFields = parseFromOrderFields(rec.getOrderFields());
        if (fromOrderFields != null && fromOrderFields.tp1() != null && fromOrderFields.sl() != null) {
            return fromOrderFields;
        }

        if (rec.getScanRun() == null || rec.getScanRun().getId() == null || rec.getSymbol() == null) {
            return null;
        }

        return symbolEvaluationRepository.findByScanRunIdAndSymbol(rec.getScanRun().getId(), rec.getSymbol())
                .map(this::parseFromMetrics)
                .orElse(null);
    }

    private PriceLevels parseFromOrderFields(OrderFields fields) {
        if (fields == null) {
            return null;
        }
        try {
            BinanceOrderFieldsDTO tp = fields.getTpOrderJson() != null
                    ? objectMapper.readValue(fields.getTpOrderJson(), BinanceOrderFieldsDTO.class)
                    : null;
            BinanceOrderFieldsDTO sl = fields.getSlOrderJson() != null
                    ? objectMapper.readValue(fields.getSlOrderJson(), BinanceOrderFieldsDTO.class)
                    : null;
            return new PriceLevels(
                    tp != null ? tp.getStopPrice() : null,
                    sl != null ? sl.getStopPrice() : null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private PriceLevels parseFromMetrics(SymbolEvaluation evaluation) {
        if (evaluation == null || evaluation.getMetricsJson() == null || evaluation.getMetricsJson().isBlank()) {
            return null;
        }
        try {
            Map<?, ?> metrics = objectMapper.readValue(evaluation.getMetricsJson(), Map.class);
            BigDecimal tp1 = parseDecimal(metrics.get("tp1"));
            BigDecimal sl = parseDecimal(metrics.get("sl"));
            return new PriceLevels(tp1, sl);
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal parseDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private RecommendationPlaceabilityDTO baseResponse(UUID recommendationId) {
        RecommendationPlaceabilityDTO dto = new RecommendationPlaceabilityDTO();
        dto.setRecommendationId(recommendationId);
        dto.setPlaceable(false);
        dto.setManualPlacementAllowed(false);
        dto.setCheckedAt(Instant.now());
        dto.setViolations(new ArrayList<>());
        dto.setAdjustments(new ArrayList<>());

        RecommendationPlaceabilityDTO.Rules rules = new RecommendationPlaceabilityDTO.Rules();
        rules.setMinTickGap(1);
        dto.setRules(rules);

        RecommendationPlaceabilityDTO.Checks checks = new RecommendationPlaceabilityDTO.Checks();
        checks.setTpOk(false);
        checks.setSlOk(false);
        checks.setRrOk(false);
        dto.setChecks(checks);

        RecommendationPlaceabilityDTO.Computed computed = new RecommendationPlaceabilityDTO.Computed();
        computed.setEntryRef("LIVE_MARK");
        dto.setComputed(computed);
        return dto;
    }

    private RecommendationPlaceabilityDTO notPlaceable(RecommendationPlaceabilityDTO dto, String reasonCode,
            String reasonText) {
        dto.setPlaceable(false);
        dto.setManualPlacementAllowed(false);
        dto.setReasonCode(reasonCode);
        dto.setReasonText(reasonText);
        return dto;
    }

    private record PriceLevels(BigDecimal tp1, BigDecimal sl) {
    }
}
