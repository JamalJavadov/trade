package com.tradebot.demo.controller;

import com.tradebot.demo.dto.DemoActionResponseDTO;
import com.tradebot.demo.dto.DemoAnalyticsSummaryResponseDTO;
import com.tradebot.demo.dto.DemoAiLatestResponseDTO;
import com.tradebot.demo.dto.DemoAiModelsStatusResponseDTO;
import com.tradebot.demo.dto.DemoStatusResponseDTO;
import com.tradebot.demo.dto.DemoTradeDetailDTO;
import com.tradebot.demo.dto.DemoTradeListResponseDTO;
import com.tradebot.demo.entity.DemoStrategyConfigVersion;
import com.tradebot.operator.RequiresPermission;
import com.tradebot.demo.service.DemoAnalyticsService;
import com.tradebot.demo.service.DemoAiSuggestionService;
import com.tradebot.demo.service.DemoStrategyConfigProvider;
import com.tradebot.demo.service.DemoTradeMonitor;
import com.tradebot.demo.service.DemoTradingLifecycleService;
import com.tradebot.demo.service.DemoTradingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/demo-trading")
@RequiredArgsConstructor
public class DemoTradingController {

    private final DemoTradingLifecycleService lifecycleService;
    private final DemoTradingQueryService queryService;
    private final DemoAiSuggestionService aiSuggestionService;
    private final DemoAnalyticsService analyticsService;
    private final DemoStrategyConfigProvider configProvider;
    private final DemoTradeMonitor demoTradeMonitor;

    @GetMapping("/status")
    public DemoStatusResponseDTO status() {
        return lifecycleService.getStatus();
    }

    @PostMapping("/enable")
    @RequiresPermission("demo.enable_disable")
    public DemoActionResponseDTO enable() {
        return lifecycleService.enable();
    }

    @PostMapping("/disable")
    @RequiresPermission("demo.enable_disable")
    public DemoActionResponseDTO disable() {
        return lifecycleService.disable();
    }

    @PostMapping("/run-once")
    @RequiresPermission("demo.run_once")
    public DemoActionResponseDTO runOnce() {
        return lifecycleService.runOnce();
    }

    @PostMapping("/reset")
    @RequiresPermission("demo.reset")
    public DemoActionResponseDTO reset(@RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            throw new IllegalArgumentException("confirm=true is required for demo reset");
        }
        return lifecycleService.reset(confirm);
    }

    @GetMapping("/trades")
    public DemoTradeListResponseDTO trades(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return queryService.getTrades(limit, offset);
    }

    @GetMapping("/open-trades")
    public DemoTradeListResponseDTO openTrades() {
        return queryService.getOpenTrades();
    }

    @GetMapping("/trades/{id}")
    public DemoTradeDetailDTO tradeById(@PathVariable UUID id) {
        return queryService.getTradeById(id);
    }

    @PostMapping("/trades/{id}/cancel")
    public DemoActionResponseDTO cancelTrade(@PathVariable UUID id) {
        demoTradeMonitor.cancelTrade(id);
        return new DemoActionResponseDTO("Demo trade cancelled", lifecycleService.getStatus().isRunning());
    }

    @GetMapping("/ai/suggestions/latest")
    public DemoAiLatestResponseDTO latestSuggestion() {
        return aiSuggestionService.getLatestProposedWithConfig();
    }

    @GetMapping("/ai/models/status")
    public DemoAiModelsStatusResponseDTO aiModelsStatus() {
        return aiSuggestionService.getModelsStatus();
    }

    @GetMapping("/analytics/summary")
    public DemoAnalyticsSummaryResponseDTO analyticsSummary(@RequestParam(defaultValue = "50") int lookback) {
        DemoAnalyticsService.AnalyticsResult analytics = analyticsService.getSummary(lookback);
        DemoStrategyConfigVersion activeVersion = configProvider.getActiveConfigVersion();

        DemoAnalyticsSummaryResponseDTO dto = new DemoAnalyticsSummaryResponseDTO();
        dto.setLookback(analytics.lookback());
        dto.setGeneratedAt(analytics.generatedAt());
        dto.setMetrics(analytics.metrics());
        dto.setCohorts(analytics.cohorts());
        dto.setTopFailurePatterns(analytics.topFailurePatterns());
        dto.setActiveConfigVersion(toConfigDto(activeVersion));
        return dto;
    }

    @PostMapping("/ai/suggestions/{batchId}/accept")
    @RequiresPermission("demo.ai.accept_reject")
    public DemoActionResponseDTO acceptSuggestion(
            @PathVariable UUID batchId,
            @RequestHeader(name = "X-Operator-Id", required = false) String operatorId) {
        aiSuggestionService.acceptBatch(batchId, operatorId == null || operatorId.isBlank() ? "local-operator" : operatorId);
        return new DemoActionResponseDTO("Demo AI batch accepted", lifecycleService.getStatus().isRunning());
    }

    @PostMapping("/ai/suggestions/{batchId}/reject")
    @RequiresPermission("demo.ai.accept_reject")
    public DemoActionResponseDTO rejectSuggestion(@PathVariable UUID batchId) {
        aiSuggestionService.rejectBatch(batchId);
        return new DemoActionResponseDTO("Demo AI batch rejected", lifecycleService.getStatus().isRunning());
    }

    @PostMapping("/ai/suggestions/generate-now")
    public DemoActionResponseDTO generateNow(@RequestParam(defaultValue = "false") boolean demoOnly) {
        aiSuggestionService.generateBatchNow(demoOnly);
        return new DemoActionResponseDTO("Demo AI batch generation attempted", lifecycleService.getStatus().isRunning());
    }

    private DemoAiLatestResponseDTO.ConfigVersionDTO toConfigDto(DemoStrategyConfigVersion version) {
        DemoAiLatestResponseDTO.ConfigVersionDTO dto = new DemoAiLatestResponseDTO.ConfigVersionDTO();
        dto.setId(version.getId());
        dto.setVersion(version.getVersion());
        dto.setCreatedAt(version.getCreatedAt());
        dto.setActive(version.getActive());
        dto.setConfigJson(version.getConfigJson());
        dto.setChangeReason(version.getChangeReason());
        return dto;
    }
}
