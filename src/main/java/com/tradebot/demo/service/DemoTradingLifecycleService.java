package com.tradebot.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradebot.config.DemoTradingProperties;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.dto.DemoActionResponseDTO;
import com.tradebot.demo.dto.DemoStatusResponseDTO;
import com.tradebot.demo.dto.DemoTradeSummaryDTO;
import com.tradebot.demo.entity.DemoAccount;
import com.tradebot.demo.entity.DemoRun;
import com.tradebot.demo.entity.DemoTrade;
import com.tradebot.demo.repository.DemoRunRepository;
import com.tradebot.demo.repository.DemoTradeRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class DemoTradingLifecycleService {

    private final DemoTradingProperties demoTradingProperties;
    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final DemoTradingScheduler demoTradingScheduler;
    private final DemoStrategyConfigService demoStrategyConfigService;
    private final DemoTradingQueryService demoTradingQueryService;
    private final DemoAccountService demoAccountService;
    private final DemoTradeRepository demoTradeRepository;
    private final DemoRunRepository demoRunRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public DemoStatusResponseDTO getStatus() {
        var demoConfig = controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading();
        DemoStatusResponseDTO dto = new DemoStatusResponseDTO();
        dto.setEnabled(demoConfig.isEnabled());
        dto.setRunning(demoTradingScheduler.isRuntimeEnabled());
        dto.setIntervalMinutes(demoConfig.getIntervalMinutes());
        dto.setMaxOpenPositions(demoConfig.getMaxOpenPositions());

        DemoStatusResponseDTO.AccountDTO accountDTO = new DemoStatusResponseDTO.AccountDTO();
        DemoAccount account = demoAccountService.getOrCreateAccount();
        accountDTO.setBalanceUsdt(account.getBalanceUsdt());
        accountDTO.setEquityUsdt(account.getEquityUsdt());
        dto.setAccount(accountDTO);

        dto.setOpenPositionsCount(demoTradeRepository.countByStatus("OPEN"));
        dto.setClosedTradesCount(demoTradeRepository.countByStatus("CLOSED"));

        DemoRun run = demoRunRepository.findFirstByOrderByStartedAtDesc().orElse(null);
        dto.setLastDemoRunStatus(run != null ? run.getStatus() : "IDLE");
        dto.setCycleCountFinished(demoRunRepository.countByStatus("FINISHED"));
        dto.setCycleCountFailed(demoRunRepository.countByStatus("FAILED"));
        dto.setCycleCountTotal(dto.getCycleCountFinished() + dto.getCycleCountFailed());
        dto.setCycleRunning(demoTradingScheduler.isCycleRunning());
        if (!dto.isRunning()) {
            dto.setWorkflowPhase("PAUSED");
        } else if (dto.isCycleRunning()) {
            dto.setWorkflowPhase("CYCLE_RUNNING");
        } else {
            dto.setWorkflowPhase("MONITORING");
        }

        DemoTrade latestTrade = demoTradeRepository.findFirstByOrderByOpenedAtDesc().orElse(null);
        DemoTradeSummaryDTO summary = latestTrade != null ? demoTradingQueryService.toSummary(latestTrade) : null;
        dto.setLastDemoTradeSummary(summary);

        dto.setLastOpenTrade(demoTradeRepository.findFirstByStatusOrderByOpenedAtDesc("OPEN")
                .map(demoTradingQueryService::toSummary)
                .orElse(null));
        dto.setLastClosedTrade(demoTradeRepository.findFirstByStatusOrderByClosedAtDesc("CLOSED")
                .map(demoTradingQueryService::toSummary)
                .orElse(null));

        long closed = demoTradeRepository.countClosedTrades();
        if (closed > 0) {
            long wins = demoTradeRepository.countWinningClosedTrades();
            dto.setWinRate(BigDecimal.valueOf(wins)
                    .multiply(new BigDecimal("100"))
                    .divide(BigDecimal.valueOf(closed), 4, java.math.RoundingMode.HALF_UP));
        } else {
            dto.setWinRate(null);
        }

        return dto;
    }

    @Transactional
    public DemoActionResponseDTO enable() {
        ObjectNode patch = objectMapper.createObjectNode();
        patch.putObject("demoTrading").put("enabled", true);
        controlCenterSettingsProvider.patch(patch, "legacy-demo-enable-endpoint", "local-operator");
        syncRuntimeWithControlCenter();
        return new DemoActionResponseDTO("Demo trading scheduler enabled", true);
    }

    @Transactional
    public DemoActionResponseDTO disable() {
        ObjectNode patch = objectMapper.createObjectNode();
        patch.putObject("demoTrading").put("enabled", false);
        controlCenterSettingsProvider.patch(patch, "legacy-demo-disable-endpoint", "local-operator");
        syncRuntimeWithControlCenter();
        return new DemoActionResponseDTO("Demo trading scheduler disabled", false);
    }

    @Transactional
    public DemoActionResponseDTO runOnce() {
        if (!controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading().isEnabled()) {
            throw new IllegalStateException("Demo trading is disabled in Control Center state (demoTrading.enabled=false).");
        }
        assertDemoBinanceKeysConfigured();
        DemoOrchestrator.CycleResult result = demoTradingScheduler.runOnceNow();
        String message = "Demo cycle " + result.status() + " (runId=" + result.runId() + ")";
        return new DemoActionResponseDTO(message, demoTradingScheduler.isRuntimeEnabled());
    }

    @Transactional
    public DemoActionResponseDTO reset(boolean confirm) {
        if (!confirm) {
            throw new IllegalArgumentException("confirm=true is required for demo reset");
        }

        demoAccountService.setModeEnabled(false);

        entityManager.createNativeQuery(
                "TRUNCATE TABLE demo_ai_call_log, demo_analytics_snapshot, demo_account_equity_event, demo_ai_suggestion_item, demo_ai_suggestion_batch, demo_trade, demo_run, demo_strategy_config_version, demo_account RESTART IDENTITY CASCADE")
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        demoAccountService.getOrCreateAccount();
        demoStrategyConfigService.ensureActiveVersion();

        return new DemoActionResponseDTO("Demo trading data reset completed", false);
    }

    @Transactional
    public void syncRuntimeWithControlCenter() {
        boolean enabled = controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading().isEnabled();
        if (enabled) {
            assertDemoBinanceKeysConfigured();
            demoAccountService.getOrCreateAccount();
            demoStrategyConfigService.ensureActiveVersion();
            demoAccountService.setModeEnabled(true);
            return;
        }
        demoAccountService.setModeEnabled(false);
    }

    void assertDemoBinanceKeysConfigured() {
        String demoKey = demoTradingProperties.getBinance() != null ? demoTradingProperties.getBinance().getApiKey() : null;
        String demoSecret = demoTradingProperties.getBinance() != null
                ? demoTradingProperties.getBinance().getApiSecret()
                : null;

        if (demoKey == null || demoKey.isBlank() || demoSecret == null || demoSecret.isBlank()) {
            throw new IllegalStateException(
                    "Demo trading requires DEMOBINANCE_API_KEY and DEMOBINANCE_API_SECRET to be configured.");
        }
    }
}
