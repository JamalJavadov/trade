package com.tradebot;

import com.tradebot.config.TraceIdFilter;
import com.tradebot.config.WebConfig;
import com.tradebot.controller.LiveTradingController;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.operator.OperatorPermissionInterceptor;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.LiveTradingExecutionService;
import com.tradebot.service.LiveTradingPreflightService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LiveTradingController.class)
@Import({ GlobalExceptionHandler.class, TraceIdFilter.class, WebConfig.class, OperatorPermissionInterceptor.class })
class LiveTradingControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LiveTradingExecutionService liveTradingExecutionService;

    @MockBean
    private LiveTradingPreflightService liveTradingPreflightService;

    @MockBean
    private LocalMutationGuard localMutationGuard;

    @MockBean
    private OperatorPermissionService operatorPermissionService;

    @Test
    void listExecutionsReturnsMostRecentAttempts() throws Exception {
        UUID recommendationId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        LiveTradeExecutionDTO dto = new LiveTradeExecutionDTO();
        dto.setId(UUID.fromString("00000000-0000-0000-0000-000000000202"));
        dto.setRecommendationId(recommendationId);
        dto.setExecutionState("OPEN");
        dto.setDryRun(false);
        dto.setCreatedAt(Instant.parse("2026-03-07T09:00:00Z"));

        when(liveTradingExecutionService.listExecutions(recommendationId, 1)).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/live-trading/executions")
                        .param("recommendationId", recommendationId.toString())
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recommendationId").value(recommendationId.toString()))
                .andExpect(jsonPath("$[0].executionState").value("OPEN"));
    }

    @Test
    void getExecutionReturnsDetail() throws Exception {
        UUID executionId = UUID.fromString("00000000-0000-0000-0000-000000000204");
        LiveTradeExecutionDTO dto = new LiveTradeExecutionDTO();
        dto.setId(executionId);
        dto.setRecommendationId(UUID.fromString("00000000-0000-0000-0000-000000000205"));
        dto.setExecutionState("RECONCILED");
        dto.setDryRun(false);

        when(liveTradingExecutionService.getExecution(executionId)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/live-trading/executions/{id}", executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(executionId.toString()))
                .andExpect(jsonPath("$.executionState").value("RECONCILED"));
    }

    @Test
    void healthReturnsStructuredRuntimeAndBinanceReadiness() throws Exception {
        LocalMutationGuard.LocalRequestCheck check = new LocalMutationGuard.LocalRequestCheck(
                true,
                "127.0.0.1",
                null,
                "http://localhost:5173",
                null);
        LiveTradingPreflightDTO dto = new LiveTradingPreflightDTO();
        dto.setExecutable(false);
        dto.getRuntime().setReadOnly(true);
        dto.getBinance().setEndpointFamily("BINANCE_FUTURES");

        when(localMutationGuard.evaluate(any())).thenReturn(check);
        when(liveTradingPreflightService.evaluateHealth(eq("BTCUSDT"), eq(check))).thenReturn(dto);

        mockMvc.perform(get("/api/v1/live-trading/health").param("symbol", "BTCUSDT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtime.readOnly").value(true))
                .andExpect(jsonPath("$.binance.endpointFamily").value("BINANCE_FUTURES"))
                .andExpect(jsonPath("$.executable").value(false));
    }
}
