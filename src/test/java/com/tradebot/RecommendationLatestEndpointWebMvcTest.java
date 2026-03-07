package com.tradebot;

import com.tradebot.config.TraceIdFilter;
import com.tradebot.controller.RecommendationController;
import com.tradebot.entity.OrderFields;
import com.tradebot.entity.Recommendation;
import com.tradebot.entity.ScanRun;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.TradeExecutionFeedbackRepository;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.LiveTradingExecutionService;
import com.tradebot.service.LiveTradingPreflightService;
import com.tradebot.service.RecommendationPlaceabilityService;
import com.tradebot.service.RecommendationQueryService;
import com.tradebot.service.SuggestionBatchService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RecommendationController.class)
@Import({ GlobalExceptionHandler.class, TraceIdFilter.class, RecommendationQueryService.class })
class RecommendationLatestEndpointWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RecommendationRepository recommendationRepository;

    @MockBean
    private TradeExecutionFeedbackRepository tradeExecutionFeedbackRepository;

    @MockBean
    private SuggestionBatchService suggestionBatchService;

    @MockBean
    private RecommendationPlaceabilityService placeabilityService;

    @MockBean
    private LiveTradingPreflightService liveTradingPreflightService;

    @MockBean
    private LiveTradingExecutionService liveTradingExecutionService;

    @MockBean
    private LocalMutationGuard localMutationGuard;

    @Test
    void latestReturns204WhenNoRecommendationExists() throws Exception {
        when(recommendationRepository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/recommendations/latest"))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertTrue(body == null || body.isBlank(),
                            "Expected empty response body when no recommendation exists.");
                });
    }

    @Test
    void latestReturns200WithValidRecommendationPayload() throws Exception {
        Recommendation rec = recommendationWithOrderFields(
                "{\"symbol\":\"BTCUSDT\",\"side\":\"BUY\",\"type\":\"MARKET\"}",
                "{\"symbol\":\"BTCUSDT\",\"side\":\"SELL\",\"type\":\"STOP_MARKET\",\"stopPrice\":98.5}",
                "{\"symbol\":\"BTCUSDT\",\"side\":\"SELL\",\"type\":\"TAKE_PROFIT_MARKET\",\"stopPrice\":105.2}");

        when(recommendationRepository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.of(rec));

        mockMvc.perform(get("/api/v1/recommendations/latest"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.id").value(rec.getId().toString()))
                .andExpect(jsonPath("$.scanRunId").value(rec.getScanRun().getId().toString()))
                .andExpect(jsonPath("$.symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$.entryOrder.symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$.slOrder.type").value("STOP_MARKET"))
                .andExpect(jsonPath("$.tpOrder.type").value("TAKE_PROFIT_MARKET"));
    }

    @Test
    void latestReturns200WithWarningWhenOrderFieldsAreMissingOrMalformed() throws Exception {
        Recommendation rec = recommendationWithOrderFields(
                null,
                "{\"side\":\"SELL\",",
                "{\"symbol\":\"BTCUSDT\",\"side\":\"SELL\",\"type\":\"TAKE_PROFIT_MARKET\",\"stopPrice\":105.2}");

        when(recommendationRepository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.of(rec));

        mockMvc.perform(get("/api/v1/recommendations/latest"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.warning").value("MISSING_ORDER_FIELDS"))
                .andExpect(jsonPath("$.entryOrder").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.slOrder").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.tpOrder.type").value("TAKE_PROFIT_MARKET"));
    }

    @Test
    void latestReturns503DbDownWhenRepositoryFails() throws Exception {
        when(recommendationRepository.findFirstByOrderByCreatedAtDesc())
                .thenThrow(new DataAccessResourceFailureException("Database unavailable"));

        mockMvc.perform(get("/api/v1/recommendations/latest"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.errorCode").value("DB_DOWN"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private Recommendation recommendationWithOrderFields(String entryOrderJson, String slOrderJson, String tpOrderJson) {
        UUID recommendationId = UUID.randomUUID();

        ScanRun scanRun = new ScanRun();
        scanRun.setId(UUID.randomUUID());

        Recommendation rec = new Recommendation();
        rec.setId(recommendationId);
        rec.setScanRun(scanRun);
        rec.setSymbol("BTCUSDT");
        rec.setSide("BUY");
        rec.setRationaleText("test rationale");
        rec.setConfidenceScore(new BigDecimal("2.50"));
        rec.setCreatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        rec.setStatus("NEW");

        OrderFields fields = new OrderFields();
        fields.setRecommendationId(recommendationId);
        fields.setRecommendation(rec);
        fields.setEntryOrderJson(entryOrderJson);
        fields.setSlOrderJson(slOrderJson);
        fields.setTpOrderJson(tpOrderJson);
        fields.setLeverageRecommendation(5);
        fields.setPositionMode("ONE_WAY");
        fields.setMarginMode("ISOLATED");

        rec.setOrderFields(fields);
        return rec;
    }
}
