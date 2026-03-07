package com.tradebot;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterController;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.operator.OperatorPermissionItemDTO;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.security.ForbiddenNotLocalException;
import com.tradebot.security.LocalMutationGuard;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ControlCenterController.class)
@Import(GlobalExceptionHandler.class)
class ControlCenterControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ControlCenterSettingsProvider controlCenterSettingsProvider;

    @MockBean
    private OperatorPermissionService operatorPermissionService;

    @MockBean
    private LocalMutationGuard localMutationGuard;

    @Test
    void getStateReturnsPermissionsSettingsAndAiRouting() throws Exception {
        ControlCenterConfig config = baseConfig();
        config.getScan().setIntervalMinutes(30);

        when(controlCenterSettingsProvider.getConfigSnapshot()).thenReturn(config);
        when(operatorPermissionService.listPermissions()).thenReturn(List.of(permissionItem(true)));

        mockMvc.perform(get("/api/v1/control-center/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0].key").value("scan.run_once"))
                .andExpect(jsonPath("$.settings.scan.intervalMinutes").value(30))
                .andExpect(jsonPath("$.aiRouting.live.allowlist[0]").value("model-live-primary"));
    }

    @Test
    void postStateAppliesPartialPatchAndPermissionUpdates() throws Exception {
        ControlCenterConfig updated = baseConfig();
        updated.getScan().setIntervalMinutes(45);

        when(controlCenterSettingsProvider.patchState(any(), any())).thenReturn(updated);
        when(controlCenterSettingsProvider.getConfigSnapshot()).thenReturn(updated);
        when(operatorPermissionService.updatePermissions(any(), eq("operator-1")))
                .thenReturn(List.of(permissionItem(false)));
        when(operatorPermissionService.listPermissions()).thenReturn(List.of(permissionItem(false)));

        mockMvc.perform(post("/api/v1/control-center/state")
                        .header("X-Operator-Id", "operator-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "permissionsUpdates": [
                                    { "key": "scan.run_once", "enabled": false }
                                  ],
                                  "settingsPatch": {
                                    "scan": { "intervalMinutes": 45 }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0].enabled").value(false))
                .andExpect(jsonPath("$.settings.scan.intervalMinutes").value(45));

        ArgumentCaptor<JsonNode> settingsPatchCaptor = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<JsonNode> aiRoutingPatchCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(controlCenterSettingsProvider).patchState(settingsPatchCaptor.capture(), aiRoutingPatchCaptor.capture());
        assertEquals(45, settingsPatchCaptor.getValue().path("scan").path("intervalMinutes").asInt());
        assertNull(aiRoutingPatchCaptor.getValue());
    }

    @Test
    void postStateBlocksNonLocalMutation() throws Exception {
        doThrow(new ForbiddenNotLocalException("10.0.0.5", "http://evil.com"))
                .when(localMutationGuard)
                .assertLocal(any());

        mockMvc.perform(post("/api/v1/control-center/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settingsPatch\":{\"scan\":{\"intervalMinutes\":10}}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("LOCAL_MUTATION_BLOCKED"));
    }

    private ControlCenterConfig baseConfig() {
        ControlCenterConfig config = new ControlCenterConfig();

        config.getAiRouting().getLive().setAllowlist(List.of("model-live-primary", "model-live-fallback"));
        config.getAiRouting().getDemo().setAllowlist(List.of("model-demo-primary"));

        ControlCenterConfig.TaskRouting liveTask = new ControlCenterConfig.TaskRouting();
        liveTask.setPrimaryModel("model-live-primary");
        liveTask.setFallbackModels(List.of("model-live-fallback"));
        liveTask.setEnabled(true);

        ControlCenterConfig.TaskRouting demoTask = new ControlCenterConfig.TaskRouting();
        demoTask.setPrimaryModel("model-demo-primary");
        demoTask.setFallbackModels(List.of());
        demoTask.setEnabled(true);

        for (String task : List.of("SUGGESTION_BATCH", "EXPLAINABILITY_TEXT", "VISION_DIAGNOSTIC")) {
            config.getAiRouting().getLive().getTasks().put(task, liveTask);
            config.getAiRouting().getDemo().getTasks().put(task, demoTask);
        }
        return config;
    }

    private OperatorPermissionItemDTO permissionItem(boolean enabled) {
        return new OperatorPermissionItemDTO(
                "scan.run_once",
                "Run Scan Once",
                "Allows one manual scan cycle.",
                "SCAN",
                "MED",
                enabled,
                null,
                "local-operator");
    }
}
