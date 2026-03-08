package com.tradebot;

import com.tradebot.config.TraceIdFilter;
import com.tradebot.config.WebConfig;
import com.tradebot.controller.RecommendationController;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.operator.ForbiddenPermissionException;
import com.tradebot.operator.OperatorPermissionInterceptor;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.TradeExecutionFeedbackRepository;
import com.tradebot.security.ForbiddenNotLocalException;
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

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RecommendationController.class)
@Import({ GlobalExceptionHandler.class, TraceIdFilter.class, WebConfig.class, OperatorPermissionInterceptor.class })
class RecommendationLiveExecutionWebMvcTest {

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

    @MockBean
    private OperatorPermissionService operatorPermissionService;

    @Test
    void executionPreflightReturnsStructuredSnapshot() throws Exception {
        UUID recommendationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        LiveTradingPreflightDTO dto = new LiveTradingPreflightDTO();
        dto.setRecommendationId(recommendationId);
        dto.setAllowed(false);
        dto.setExecutable(false);
        dto.getRuntime().setReadOnly(true);
        dto.getRuntime().setLiveExecutionEnabled(true);
        dto.getBinance().setBlockerCode("BINANCE_AUTH_INVALID");

        LocalMutationGuard.LocalRequestCheck check = new LocalMutationGuard.LocalRequestCheck(
                true,
                "127.0.0.1",
                null,
                "http://localhost:5173",
                null);
        when(localMutationGuard.evaluate(any())).thenReturn(check);
        when(liveTradingPreflightService.evaluate(recommendationId, null, check)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/recommendations/{id}/execution-preflight", recommendationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").value(recommendationId.toString()))
                .andExpect(jsonPath("$.executable").value(false))
                .andExpect(jsonPath("$.runtime.readOnly").value(true))
                .andExpect(jsonPath("$.binance.blockerCode").value("BINANCE_AUTH_INVALID"));
    }

    @Test
    void executionPreflightPreservesCredentialDecryptFailureCode() throws Exception {
        UUID recommendationId = UUID.fromString("00000000-0000-0000-0000-000000000107");
        LiveTradingPreflightDTO dto = new LiveTradingPreflightDTO();
        dto.setRecommendationId(recommendationId);
        dto.setAllowed(false);
        dto.setExecutable(false);
        dto.getBinance().setBlockerCode("CREDENTIAL_DECRYPT_FAILED");
        dto.getSummary().setPrimaryBlockerCode("CREDENTIAL_DECRYPT_FAILED");
        dto.getSummary().setPrimaryBlockerMessage("Binance credentials could not be decrypted. Re-save credentials in Settings.");

        LocalMutationGuard.LocalRequestCheck check = new LocalMutationGuard.LocalRequestCheck(
                true,
                "127.0.0.1",
                null,
                "http://localhost:5173",
                null);
        when(localMutationGuard.evaluate(any())).thenReturn(check);
        when(liveTradingPreflightService.evaluate(recommendationId, null, check)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/recommendations/{id}/execution-preflight", recommendationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binance.blockerCode").value("CREDENTIAL_DECRYPT_FAILED"))
                .andExpect(jsonPath("$.summary.primaryBlockerCode").value("CREDENTIAL_DECRYPT_FAILED"))
                .andExpect(jsonPath("$.summary.primaryBlockerMessage")
                        .value("Binance credentials could not be decrypted. Re-save credentials in Settings."));
    }

    @Test
    void executeLiveRequiresOperatorPermission() throws Exception {
        doThrow(new ForbiddenPermissionException("live.execution.enabled", "Enable Live Execution"))
                .when(operatorPermissionService)
                .requirePermissionEnabled("live.execution.enabled");

        mockMvc.perform(post("/api/v1/recommendations/{id}/execute-live",
                        "00000000-0000-0000-0000-000000000102")
                        .contentType(APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"00000000-0000-0000-0000-000000000099\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("RUNTIME_PERMISSION_DISABLED"))
                .andExpect(jsonPath("$.details.permissionKey").value("live.execution.enabled"));
    }

    @Test
    void executeLiveRejectsNonLocalMutation() throws Exception {
        UUID recommendationId = UUID.fromString("00000000-0000-0000-0000-000000000103");
        LocalMutationGuard.LocalRequestCheck denied = new LocalMutationGuard.LocalRequestCheck(
                false,
                "10.0.0.12",
                null,
                "https://remote-host",
                "Remote address is not loopback.");
        when(localMutationGuard.evaluate(any())).thenReturn(denied);
        doThrow(new ForbiddenNotLocalException("10.0.0.12", "https://remote-host"))
                .when(localMutationGuard)
                .assertLocalCheck(denied);

        mockMvc.perform(post("/api/v1/recommendations/{id}/execute-live", recommendationId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"00000000-0000-0000-0000-000000000199\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("LOCAL_MUTATION_BLOCKED"));
    }

    @Test
    void executeLiveReturnsExecutionStateAfterExplicitConfirmation() throws Exception {
        UUID recommendationId = UUID.fromString("00000000-0000-0000-0000-000000000104");
        LocalMutationGuard.LocalRequestCheck allowed = new LocalMutationGuard.LocalRequestCheck(
                true,
                "127.0.0.1",
                null,
                "http://localhost:5173",
                null);
        LiveTradeExecutionDTO dto = new LiveTradeExecutionDTO();
        dto.setId(UUID.fromString("00000000-0000-0000-0000-000000000105"));
        dto.setRecommendationId(recommendationId);
        dto.setExecutionState("OPEN");
        dto.setDryRun(false);
        dto.setCreatedAt(Instant.parse("2026-03-07T09:00:00Z"));

        when(localMutationGuard.evaluate(any())).thenReturn(allowed);
        when(liveTradingExecutionService.executeLive(eq(recommendationId), any(), eq("local-operator"), any(), eq(allowed)))
                .thenReturn(dto);

        mockMvc.perform(post("/api/v1/recommendations/{id}/execute-live", recommendationId)
                        .header("Origin", "http://localhost:5173")
                        .header("X-Operator-Id", "local-operator")
                        .contentType(APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"00000000-0000-0000-0000-000000000299\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").value(recommendationId.toString()))
                .andExpect(jsonPath("$.executionState").value("OPEN"))
                .andExpect(jsonPath("$.dryRun").value(false));
    }

    @Test
    void executeLiveRejectsMissingClientRequestId() throws Exception {
        mockMvc.perform(post("/api/v1/recommendations/{id}/execute-live",
                        "00000000-0000-0000-0000-000000000106")
                        .contentType(APPLICATION_JSON)
                        .content("{\"operatorNote\":\"test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION"))
                .andExpect(jsonPath("$.details.fieldErrors.clientRequestId").value("clientRequestId is required"));
    }
}
