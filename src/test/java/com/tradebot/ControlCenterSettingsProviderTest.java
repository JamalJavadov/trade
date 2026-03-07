package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradebot.controlcenter.ControlCenterCache;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.service.DemoTradingLifecycleService;
import com.tradebot.entity.ControlCenterStateEntity;
import com.tradebot.operator.OperatorPermissionRepository;
import com.tradebot.operator.PermissionCatalog;
import com.tradebot.repository.AppSettingsRepository;
import com.tradebot.repository.ControlCenterStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlCenterSettingsProviderTest {

    @Test
    void patchBackfillsMissingScanReviewBeforeValidationAndPersistsNormalizedState() {
        ControlCenterStateRepository stateRepository = mock(ControlCenterStateRepository.class);
        AppSettingsRepository appSettingsRepository = mock(AppSettingsRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OperatorPermissionRepository> operatorPermissionRepositoryProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DemoTradingLifecycleService> demoLifecycleProvider = mock(ObjectProvider.class);
        when(operatorPermissionRepositoryProvider.getIfAvailable()).thenReturn(null);
        when(demoLifecycleProvider.getIfAvailable()).thenReturn(null);

        ObjectMapper objectMapper = new ObjectMapper();
        ControlCenterSettingsProvider provider = new ControlCenterSettingsProvider(
                stateRepository,
                appSettingsRepository,
                operatorPermissionRepositoryProvider,
                new PermissionCatalog(),
                demoLifecycleProvider,
                objectMapper,
                new ControlCenterCache());

        ControlCenterStateEntity entity = new ControlCenterStateEntity();
        entity.setId(1);
        entity.setVersion(4);
        entity.setUpdatedAt(Instant.parse("2026-03-07T00:00:00Z"));
        entity.setUpdatedBy("seed");
        entity.setConfigJson("""
                {
                  "permissions": {
                    "settings.update": true
                  },
                  "scan": {
                    "autoscanEnabled": false,
                    "intervalMinutes": 20,
                    "safeMode": false
                  },
                  "risk": {
                    "budgetUsdt": 50,
                    "maxBudgetPct": 5,
                    "equityOverrideUsdt": null,
                    "maxEquityPctLocked": 1
                  },
                  "alerts": {
                    "enabled": true,
                    "volume": 0.9,
                    "durationSeconds": 10
                  },
                  "ai": {
                    "enabled": true,
                    "allowlist": [
                      "stepfun/step-3.5-flash:free",
                      "z-ai/glm-4.5-air:free",
                      "nvidia/nemotron-nano-9b-v2:free",
                      "openai/gpt-oss-120b:free"
                    ],
                    "live": {
                      "routing": {
                        "suggestion": {
                          "primaryModel": "stepfun/step-3.5-flash:free",
                          "fallbackModels": []
                        },
                        "explainability": {
                          "primaryModel": "stepfun/step-3.5-flash:free",
                          "fallbackModels": []
                        },
                        "vision": {
                          "primaryModel": "openai/gpt-oss-120b:free",
                          "fallbackModels": []
                        }
                      }
                    },
                    "demo": {
                      "routing": {
                        "suggestion": {
                          "primaryModel": "openai/gpt-oss-120b:free",
                          "fallbackModels": []
                        },
                        "explainability": {
                          "primaryModel": "stepfun/step-3.5-flash:free",
                          "fallbackModels": []
                        },
                        "vision": {
                          "primaryModel": "openai/gpt-oss-120b:free",
                          "fallbackModels": []
                        }
                      }
                    }
                  },
                  "demoTrading": {
                    "enabled": false,
                    "intervalMinutes": 15,
                    "maxOpenPositions": 1,
                    "startBalanceUsdt": 1000,
                    "riskPct": 0.5,
                    "leverageDefault": 5,
                    "feeBps": 4,
                    "slippageBps": 2,
                    "timeStopMinutes": 90
                  },
                  "strategyLocks": {
                    "executionTf": "15m",
                    "biasTf": "1h",
                    "fractalPeriod": 5,
                    "minRr": 2
                  }
                }
                """);

        when(stateRepository.findById(1)).thenReturn(Optional.of(entity));
        when(stateRepository.save(any(ControlCenterStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ObjectNode noOpPatch = objectMapper.createObjectNode();
        ControlCenterConfig result = provider.patch(noOpPatch, "noop", "tester");

        assertThat(result.getAi().getLive().getRouting().getScanReview().getPrimaryModel()).isNotBlank();
        assertThat(result.getAi().getDemo().getRouting().getScanReview().getPrimaryModel()).isNotBlank();
        verify(stateRepository, atLeastOnce()).save(any(ControlCenterStateEntity.class));
        assertThat(entity.getConfigJson()).contains("\"scanReview\"");
    }

    @Test
    void patchMigratesLegacyLiveExecutionKeysAndRemovesStaleLiveTradingConfig() {
        ControlCenterStateRepository stateRepository = mock(ControlCenterStateRepository.class);
        AppSettingsRepository appSettingsRepository = mock(AppSettingsRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OperatorPermissionRepository> operatorPermissionRepositoryProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DemoTradingLifecycleService> demoLifecycleProvider = mock(ObjectProvider.class);
        when(operatorPermissionRepositoryProvider.getIfAvailable()).thenReturn(null);
        when(demoLifecycleProvider.getIfAvailable()).thenReturn(null);

        ObjectMapper objectMapper = new ObjectMapper();
        ControlCenterSettingsProvider provider = new ControlCenterSettingsProvider(
                stateRepository,
                appSettingsRepository,
                operatorPermissionRepositoryProvider,
                new PermissionCatalog(),
                demoLifecycleProvider,
                objectMapper,
                new ControlCenterCache());

        ControlCenterStateEntity entity = new ControlCenterStateEntity();
        entity.setId(1);
        entity.setVersion(7);
        entity.setUpdatedAt(Instant.parse("2026-03-07T00:00:00Z"));
        entity.setUpdatedBy("seed");
        entity.setConfigJson("""
                {
                  "permissions": {
                    "settings.update": true,
                    "live.execution.view": false,
                    "live.execution.run": false,
                    "live.execution.reconcile": true
                  },
                  "liveTrading": {
                    "enabled": true,
                    "manualExecutionEnabled": true,
                    "armed": false,
                    "killSwitch": true,
                    "dryRun": true
                  }
                }
                """);

        when(stateRepository.findById(1)).thenReturn(Optional.of(entity));
        when(stateRepository.save(any(ControlCenterStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ControlCenterConfig result = provider.patch(objectMapper.createObjectNode(), "noop", "tester");

        assertThat(result.getPermissions())
                .containsEntry("live.execution.enabled", false)
                .doesNotContainKeys("live.execution.view", "live.execution.run", "live.execution.reconcile");
        assertThat(result.getLiveExecution().isReadOnly()).isTrue();
        assertThat(entity.getConfigJson()).contains("\"live.execution.enabled\":false");
        assertThat(entity.getConfigJson()).contains("\"liveExecution\":{\"readOnly\":true}");
        assertThat(entity.getConfigJson()).doesNotContain("liveTrading");
        assertThat(entity.getConfigJson()).doesNotContain("live.execution.view");
        assertThat(entity.getConfigJson()).doesNotContain("live.execution.run");
        assertThat(entity.getConfigJson()).doesNotContain("live.execution.reconcile");
    }
}
