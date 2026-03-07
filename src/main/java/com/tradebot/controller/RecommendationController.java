package com.tradebot.controller;

import com.tradebot.dto.BinanceOrderFieldsDTO;
import com.tradebot.dto.FeedbackRequestDTO;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.dto.LiveTradeExecutionRequestDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.dto.RecommendationDTO;
import com.tradebot.dto.RecommendationPlaceabilityDTO;
import com.tradebot.entity.Recommendation;
import com.tradebot.entity.OrderFields;
import com.tradebot.operator.RequiresPermission;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.LiveTradingExecutionService;
import com.tradebot.service.LiveTradingPreflightService;
import com.tradebot.trace.TraceIdContext;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.UUID;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import com.tradebot.entity.TradeExecutionFeedback;
import com.tradebot.repository.TradeExecutionFeedbackRepository;
import com.tradebot.service.RecommendationPlaceabilityService;
import com.tradebot.service.RecommendationQueryService;
import com.tradebot.service.SuggestionBatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationRepository recommendationRepository;
    private final TradeExecutionFeedbackRepository feedbackRepository;
    private final SuggestionBatchService suggestionBatchService;
    private final RecommendationPlaceabilityService placeabilityService;
    private final RecommendationQueryService recommendationQueryService;
    private final LiveTradingPreflightService liveTradingPreflightService;
    private final LiveTradingExecutionService liveTradingExecutionService;
    private final LocalMutationGuard localMutationGuard;
    private final ObjectMapper objectMapper;

    @GetMapping("/latest")
    public ResponseEntity<RecommendationDTO> getLatest() {
        RecommendationDTO dto = recommendationQueryService.getLatestRecommendation();
        if (dto == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/{id}")
    public RecommendationDTO getById(@PathVariable UUID id) throws Exception {
        Recommendation rec = recommendationRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Recommendation not found: " + id));

        RecommendationDTO dto = new RecommendationDTO();
        dto.setId(rec.getId());
        dto.setScanRunId(rec.getScanRun().getId());
        dto.setSymbol(rec.getSymbol());
        dto.setSide(rec.getSide());
        dto.setRationaleText(rec.getRationaleText());
        dto.setConfidenceScore(rec.getConfidenceScore());
        dto.setCreatedAt(rec.getCreatedAt());
        dto.setStatus(rec.getStatus());

        OrderFields fields = rec.getOrderFields();
        if (fields != null) {
            dto.setEntryOrder(objectMapper.readValue(fields.getEntryOrderJson(), BinanceOrderFieldsDTO.class));
            dto.setSlOrder(objectMapper.readValue(fields.getSlOrderJson(), BinanceOrderFieldsDTO.class));
            dto.setTpOrder(objectMapper.readValue(fields.getTpOrderJson(), BinanceOrderFieldsDTO.class));
            dto.setLeverageRecommendation(fields.getLeverageRecommendation());
            dto.setPositionMode(fields.getPositionMode());
            dto.setMarginMode(fields.getMarginMode());
        }

        return dto;
    }

    @GetMapping("/{id}/placeability")
    public RecommendationPlaceabilityDTO getPlaceability(@PathVariable UUID id) {
        return placeabilityService.evaluate(id);
    }

    @GetMapping("/{id}/execution-preflight")
    public LiveTradingPreflightDTO getExecutionPreflight(@PathVariable UUID id, HttpServletRequest httpServletRequest) {
        return liveTradingPreflightService.evaluate(id, null, localMutationGuard.evaluate(httpServletRequest));
    }

    @PostMapping("/{id}/execute-live")
    @RequiresPermission("live.execution.enabled")
    public ResponseEntity<LiveTradeExecutionDTO> executeLive(@PathVariable UUID id,
            @Valid @RequestBody LiveTradeExecutionRequestDTO request,
            @RequestHeader(name = "X-Operator-Id", required = false) String operatorId,
            HttpServletRequest httpServletRequest) {
        LocalMutationGuard.LocalRequestCheck localRequestCheck = localMutationGuard.evaluate(httpServletRequest);
        localMutationGuard.assertLocalCheck(localRequestCheck);
        String traceId = TraceIdContext.resolveOrCreate(httpServletRequest);
        LiveTradeExecutionDTO execution = liveTradingExecutionService.executeLive(
                id,
                request,
                operatorId,
                traceId,
                localRequestCheck);
        return ResponseEntity.ok(execution);
    }

    @PostMapping("/{id}/feedback")
    @RequiresPermission("journal.feedback.submit")
    public void submitFeedback(@PathVariable UUID id, @RequestBody FeedbackRequestDTO feedback) {
        Recommendation rec = recommendationRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Recommendation not found: " + id));

        TradeExecutionFeedback ex = new TradeExecutionFeedback();
        ex.setRecommendation(rec);
        ex.setUserLabel(feedback.getUserLabel());
        ex.setPnlUsdt(feedback.getPnlUsdt());
        ex.setRMultiple(feedback.getRMultiple());
        ex.setNotes(feedback.getNotes());
        ex.setCreatedAt(Instant.now());
        feedbackRepository.save(ex);

        // Async trigger or synchronous trigger
        suggestionBatchService.generateBatchIfNeeded();
    }
}
