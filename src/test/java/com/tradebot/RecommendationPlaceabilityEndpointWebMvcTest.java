package com.tradebot;

import com.tradebot.config.TraceIdFilter;
import com.tradebot.controller.RecommendationController;
import com.tradebot.dto.RecommendationPlaceabilityDTO;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.TradeExecutionFeedbackRepository;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.LiveTradingExecutionService;
import com.tradebot.service.LiveTradingPreflightService;
import com.tradebot.service.RecommendationPlaceabilityService;
import com.tradebot.service.RecommendationQueryService;
import com.tradebot.service.SuggestionBatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RecommendationController.class)
@Import({ GlobalExceptionHandler.class, TraceIdFilter.class })
class RecommendationPlaceabilityEndpointWebMvcTest {

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
    private RecommendationQueryService recommendationQueryService;

    @MockBean
    private LiveTradingPreflightService liveTradingPreflightService;

    @MockBean
    private LiveTradingExecutionService liveTradingExecutionService;

    @MockBean
    private LocalMutationGuard localMutationGuard;

    @Test
    void endpointReturnsStableJsonForValidUuid() throws Exception {
        UUID id = UUID.randomUUID();

        RecommendationPlaceabilityDTO dto = new RecommendationPlaceabilityDTO();
        dto.setRecommendationId(id);
        dto.setSymbol("BTCUSDT");
        dto.setSide("LONG");
        dto.setMarkPrice(new BigDecimal("100.0"));
        dto.setTickSize(new BigDecimal("0.1"));
        dto.setPlaceable(false);
        dto.setReasonCode("RR_BELOW_2");
        dto.setReasonText("Live RR to TP1 is below the minimum required ratio.");
        dto.setViolations(List.of("Live RR to TP1 (1.2) is below minimum required (2.0)."));
        dto.setAdjustments(List.of());

        RecommendationPlaceabilityDTO.Rules rules = new RecommendationPlaceabilityDTO.Rules();
        rules.setInequalityRule("TP > MARK > SL");
        rules.setMinTickGap(1);
        dto.setRules(rules);

        RecommendationPlaceabilityDTO.Checks checks = new RecommendationPlaceabilityDTO.Checks();
        checks.setTpOk(true);
        checks.setSlOk(true);
        checks.setRrOk(false);
        dto.setChecks(checks);

        RecommendationPlaceabilityDTO.Computed computed = new RecommendationPlaceabilityDTO.Computed();
        computed.setEntryRef("LIVE_MARK");
        computed.setRrToTp1(new BigDecimal("1.2"));
        computed.setTp1(new BigDecimal("101.2"));
        computed.setSl(new BigDecimal("99.0"));
        dto.setComputed(computed);

        when(placeabilityService.evaluate(id)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/recommendations/{id}/placeability", id))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.recommendationId").value(id.toString()))
                .andExpect(jsonPath("$.reasonCode").value("RR_BELOW_2"))
                .andExpect(jsonPath("$.placeable").value(false));
    }

    @Test
    void invalidUuidReturnsValidationNotInternal() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations/not-a-uuid/placeability"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION"));
    }
}
