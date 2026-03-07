package com.tradebot;

import com.tradebot.config.TraceIdFilter;
import com.tradebot.controlcenter.ControlCenterController;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.controller.AiModelController;
import com.tradebot.controller.RecommendationController;
import com.tradebot.controller.SettingsController;
import com.tradebot.controller.StatusController;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.operator.PermissionCatalog;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.ScanRunRepository;
import com.tradebot.repository.TradeExecutionFeedbackRepository;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.AiModelSettingsService;
import com.tradebot.service.AppSettingsService;
import com.tradebot.service.AutoScanStateService;
import com.tradebot.service.LiveTradingExecutionService;
import com.tradebot.service.LiveTradingPreflightService;
import com.tradebot.service.RecommendationPlaceabilityService;
import com.tradebot.service.RecommendationQueryService;
import com.tradebot.service.ScanOrchestrator;
import com.tradebot.service.SuggestionBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AiModelController.class,
        StatusController.class,
        SettingsController.class,
        ControlCenterController.class,
        RecommendationController.class
})
@Import({ GlobalExceptionHandler.class, TraceIdFilter.class })
class AffectedEndpointsDbFailureWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiModelSettingsService aiModelSettingsService;

    @MockBean
    private LocalMutationGuard localMutationGuard;

    @MockBean
    private ScanRunRepository scanRunRepository;

    @MockBean
    private RecommendationRepository recommendationRepository;

    @MockBean
    private AppSettingsService appSettingsService;

    @MockBean
    private ScanOrchestrator scanOrchestrator;

    @MockBean
    private AutoScanStateService autoScanStateService;

    @MockBean
    private OperatorPermissionService operatorPermissionService;

    @MockBean
    private ControlCenterSettingsProvider controlCenterSettingsProvider;

    @MockBean
    private PermissionCatalog permissionCatalog;

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

    @BeforeEach
    void setUp() {
        when(permissionCatalog.all()).thenReturn(List.of());
    }

    @Test
    void aiModelsMapsDbTransactionFailureTo503() throws Exception {
        when(aiModelSettingsService.getLiveSettings())
                .thenThrow(new CannotCreateTransactionException("db unavailable"));

        expectDbDown("/api/v1/ai/models");
    }

    @Test
    void statusMapsDbTransactionFailureTo503() throws Exception {
        when(autoScanStateService.buildState())
                .thenThrow(new CannotCreateTransactionException("db unavailable"));

        expectDbDown("/api/v1/status");
    }

    @Test
    void settingsMapsDbTransactionFailureTo503() throws Exception {
        when(appSettingsService.getSettings())
                .thenThrow(new CannotCreateTransactionException("db unavailable"));

        expectDbDown("/api/v1/settings");
    }

    @Test
    void controlCenterStateMapsDbTransactionFailureTo503() throws Exception {
        when(controlCenterSettingsProvider.getConfigSnapshot())
                .thenThrow(new CannotCreateTransactionException("db unavailable"));

        expectDbDown("/api/v1/control-center/state");
    }

    @Test
    void latestRecommendationMapsDbTransactionFailureTo503() throws Exception {
        when(recommendationQueryService.getLatestRecommendation())
                .thenThrow(new CannotCreateTransactionException("db unavailable"));

        expectDbDown("/api/v1/recommendations/latest");
    }

    private void expectDbDown(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.errorCode").value("DB_DOWN"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.details.rootExceptionClass").value("CannotCreateTransactionException"))
                .andExpect(jsonPath("$.details.rootMessage").value("db unavailable"));
    }
}
