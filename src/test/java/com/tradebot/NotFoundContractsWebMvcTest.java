package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.controller.RecommendationController;
import com.tradebot.controller.ScanController;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.TradeExecutionFeedbackRepository;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.AutoScanStateService;
import com.tradebot.service.LiveTradingExecutionService;
import com.tradebot.service.LiveTradingPreflightService;
import com.tradebot.service.RecommendationPlaceabilityService;
import com.tradebot.service.RecommendationQueryService;
import com.tradebot.service.ScanOrchestrator;
import com.tradebot.service.ScanQueryService;
import com.tradebot.service.SuggestionBatchService;
import com.tradebot.sse.ScanStreamRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotFoundContractsWebMvcTest {

    @Test
    void missingRecommendationReturns404Envelope() throws Exception {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        when(recommendationRepository.findById(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .thenReturn(Optional.empty());

        RecommendationController controller = new RecommendationController(
                recommendationRepository,
                mock(TradeExecutionFeedbackRepository.class),
                mock(SuggestionBatchService.class),
                mock(RecommendationPlaceabilityService.class),
                mock(RecommendationQueryService.class),
                mock(LiveTradingPreflightService.class),
                mock(LiveTradingExecutionService.class),
                mock(LocalMutationGuard.class),
                new ObjectMapper());

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/v1/recommendations/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void missingScanSummaryReturns404Envelope() throws Exception {
        ScanQueryService scanQueryService = mock(ScanQueryService.class);
        UUID scanRunId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(scanQueryService.getScanSummary(scanRunId))
                .thenThrow(new NoSuchElementException("Scan run not found: " + scanRunId));

        ScanController controller = new ScanController(
                mock(ScanOrchestrator.class),
                mock(AutoScanStateService.class),
                scanQueryService,
                mock(ScanStreamRegistry.class));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/v1/scans/00000000-0000-0000-0000-000000000002"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void missingScanReplayReturns404Envelope() throws Exception {
        ScanQueryService scanQueryService = mock(ScanQueryService.class);
        UUID scanRunId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        when(scanQueryService.getReplay(scanRunId))
                .thenThrow(new NoSuchElementException("Scan run not found: " + scanRunId));

        ScanController controller = new ScanController(
                mock(ScanOrchestrator.class),
                mock(AutoScanStateService.class),
                scanQueryService,
                mock(ScanStreamRegistry.class));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/v1/scans/00000000-0000-0000-0000-000000000003/replay"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }
}
