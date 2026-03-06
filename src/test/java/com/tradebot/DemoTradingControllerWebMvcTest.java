package com.tradebot;

import com.tradebot.demo.controller.DemoTradingController;
import com.tradebot.demo.dto.DemoActionResponseDTO;
import com.tradebot.demo.dto.DemoAiLatestResponseDTO;
import com.tradebot.demo.dto.DemoAiModelsStatusResponseDTO;
import com.tradebot.demo.dto.DemoStatusResponseDTO;
import com.tradebot.demo.dto.DemoTradeDetailDTO;
import com.tradebot.demo.dto.DemoTradeListResponseDTO;
import com.tradebot.demo.entity.DemoStrategyConfigVersion;
import com.tradebot.demo.entity.DemoTrade;
import com.tradebot.demo.service.DemoAnalyticsService;
import com.tradebot.demo.service.DemoAiSuggestionService;
import com.tradebot.demo.service.DemoStrategyConfigProvider;
import com.tradebot.demo.service.DemoTradeMonitor;
import com.tradebot.demo.service.DemoTradingLifecycleService;
import com.tradebot.demo.service.DemoTradingQueryService;
import com.tradebot.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DemoTradingController.class)
@Import(GlobalExceptionHandler.class)
class DemoTradingControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DemoTradingLifecycleService lifecycleService;

    @MockBean
    private DemoTradingQueryService queryService;

    @MockBean
    private DemoAiSuggestionService aiSuggestionService;

    @MockBean
    private DemoAnalyticsService analyticsService;

    @MockBean
    private DemoStrategyConfigProvider configProvider;

    @MockBean
    private DemoTradeMonitor demoTradeMonitor;

    @Test
    void statusReturns200AndRequiredFields() throws Exception {
        DemoStatusResponseDTO statusDTO = new DemoStatusResponseDTO();
        statusDTO.setEnabled(true);
        statusDTO.setRunning(false);
        statusDTO.setIntervalMinutes(15);
        statusDTO.setMaxOpenPositions(1);
        DemoStatusResponseDTO.AccountDTO accountDTO = new DemoStatusResponseDTO.AccountDTO();
        accountDTO.setBalanceUsdt(new BigDecimal("1000"));
        accountDTO.setEquityUsdt(new BigDecimal("1000"));
        statusDTO.setAccount(accountDTO);
        statusDTO.setOpenPositionsCount(0);
        statusDTO.setLastDemoRunStatus("IDLE");

        when(lifecycleService.getStatus()).thenReturn(statusDTO);

        mockMvc.perform(get("/api/v1/demo-trading/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.intervalMinutes").value(15))
                .andExpect(jsonPath("$.account.balanceUsdt").value(1000));
    }

    @Test
    void enableDisableAndRunOnceReturn200() throws Exception {
        when(lifecycleService.enable()).thenReturn(new DemoActionResponseDTO("enabled", true));
        when(lifecycleService.disable()).thenReturn(new DemoActionResponseDTO("disabled", false));
        when(lifecycleService.runOnce()).thenReturn(new DemoActionResponseDTO("cycle", true));

        mockMvc.perform(post("/api/v1/demo-trading/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true));

        mockMvc.perform(post("/api/v1/demo-trading/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false));

        mockMvc.perform(post("/api/v1/demo-trading/run-once"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("cycle"));
    }

    @Test
    void resetRequiresConfirm() throws Exception {
        mockMvc.perform(post("/api/v1/demo-trading/reset"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));

        when(lifecycleService.reset(true)).thenReturn(new DemoActionResponseDTO("reset", false));

        mockMvc.perform(post("/api/v1/demo-trading/reset").param("confirm", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false));
    }

    @Test
    void tradesOpenTradesAndTradeByIdRoutesWork() throws Exception {
        DemoTradeListResponseDTO list = new DemoTradeListResponseDTO();
        list.setLimit(20);
        list.setOffset(0);
        list.setTotal(0);
        list.setTrades(List.of());
        when(queryService.getTrades(20, 0)).thenReturn(list);
        when(queryService.getOpenTrades()).thenReturn(list);

        mockMvc.perform(get("/api/v1/demo-trading/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(20));

        mockMvc.perform(get("/api/v1/demo-trading/open-trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(20));

        UUID id = UUID.randomUUID();
        DemoTradeDetailDTO detail = new DemoTradeDetailDTO();
        detail.setId(id);
        detail.setSymbol("BTCUSDT");
        when(queryService.getTradeById(id)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/demo-trading/trades/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("BTCUSDT"));
    }

    @Test
    void cancelRouteReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(demoTradeMonitor.cancelTrade(id)).thenReturn(new DemoTrade());
        when(lifecycleService.getStatus()).thenReturn(new DemoStatusResponseDTO());

        mockMvc.perform(post("/api/v1/demo-trading/trades/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Demo trade cancelled"));
    }

    @Test
    void aiRoutesReturn200() throws Exception {
        when(aiSuggestionService.getLatestProposedWithConfig()).thenReturn(new DemoAiLatestResponseDTO());
        DemoAiModelsStatusResponseDTO modelsStatus = new DemoAiModelsStatusResponseDTO();
        modelsStatus.setEnabled(true);
        modelsStatus.setRouting(java.util.Map.of("SUGGESTION_BATCH", java.util.List.of("model-a", "model-b")));
        when(aiSuggestionService.getModelsStatus()).thenReturn(modelsStatus);
        when(lifecycleService.getStatus()).thenReturn(new DemoStatusResponseDTO());

        mockMvc.perform(get("/api/v1/demo-trading/ai/suggestions/latest"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/demo-trading/ai/models/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.routing.SUGGESTION_BATCH[0]").value("model-a"))
                .andExpect(jsonPath("$.lastCall").value(nullValue()));

        UUID batchId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/demo-trading/ai/suggestions/{batchId}/accept", batchId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/demo-trading/ai/suggestions/{batchId}/reject", batchId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/demo-trading/ai/suggestions/generate-now").param("demoOnly", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void analyticsSummaryRouteReturns200() throws Exception {
        when(analyticsService.getSummary(10)).thenReturn(new DemoAnalyticsService.AnalyticsResult(
                10, java.time.Instant.now(), java.util.Map.of("total", 10), java.util.Map.of(), java.util.List.of()));

        DemoStrategyConfigVersion version = new DemoStrategyConfigVersion();
        version.setId(UUID.randomUUID());
        version.setVersion(2);
        version.setActive(true);
        version.setConfigJson("{}");
        when(configProvider.getActiveConfigVersion()).thenReturn(version);

        mockMvc.perform(get("/api/v1/demo-trading/analytics/summary").param("lookback", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lookback").value(10))
                .andExpect(jsonPath("$.metrics.total").value(10));
    }
}
