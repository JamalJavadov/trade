package com.tradebot.controller;

import com.tradebot.dto.FeedbackRequestDTO;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.dto.LiveTradeExecutionRequestDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.dto.RecommendationDTO;
import com.tradebot.dto.RecommendationPlaceabilityDTO;
import com.tradebot.entity.Recommendation;
import com.tradebot.operator.RequiresPermission;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.LiveTradingExecutionService;
import com.tradebot.service.LiveTradingPreflightService;
import com.tradebot.trace.TraceIdContext;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import com.tradebot.entity.TradeExecutionFeedback;
import com.tradebot.repository.TradeExecutionFeedbackRepository;
import com.tradebot.service.RecommendationPlaceabilityService;
import com.tradebot.service.RecommendationQueryService;
import com.tradebot.service.SuggestionBatchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

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

    @GetMapping("/latest")
    public ResponseEntity<RecommendationDTO> getLatest() {
        RecommendationDTO dto = recommendationQueryService.getLatestRecommendation();
        if (dto == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/{id}")
    public RecommendationDTO getById(@PathVariable UUID id) {
        return recommendationQueryService.getRecommendation(id);
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
