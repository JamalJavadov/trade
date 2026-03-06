package com.tradebot;

import com.tradebot.controller.SettingsController;
import com.tradebot.entity.AppSettings;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.ScanRunRepository;
import com.tradebot.service.AppSettingsService;
import com.tradebot.service.ScanOrchestrator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SettingsController.class)
@Import(GlobalExceptionHandler.class)
class SettingsControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppSettingsService appSettingsService;

    @MockBean
    private ScanOrchestrator scanOrchestrator;

    @MockBean
    private OperatorPermissionService operatorPermissionService;

    @MockBean
    private LocalMutationGuard localMutationGuard;

    // Not used directly in these tests, but present in context for other controllers.
    @MockBean
    private ScanRunRepository scanRunRepository;

    @MockBean
    private RecommendationRepository recommendationRepository;

    @Test
    void postPartialBooleanUpdate_allowsMissingInterval() throws Exception {
        AppSettings updated = buildSettings();
        updated.setSchedulerEnabled(false);

        when(scanOrchestrator.isScanRunning()).thenReturn(false);
        when(appSettingsService.updateSettings(any())).thenReturn(updated);

        mockMvc.perform(post("/api/v1/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schedulerEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulerEnabled").value(false))
                .andExpect(jsonPath("$.scanIntervalMinutes").value(20));

        ArgumentCaptor<com.tradebot.dto.SettingsUpdateRequestDTO> captor = ArgumentCaptor.forClass(
                com.tradebot.dto.SettingsUpdateRequestDTO.class);
        verify(appSettingsService).updateSettings(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getSchedulerEnabled());
        assertNull(captor.getValue().getScanIntervalMinutes());
    }

    @Test
    void postAliasIntervalMinutes_mapsToScanIntervalMinutes() throws Exception {
        AppSettings updated = buildSettings();
        updated.setScanIntervalMinutes(45);

        when(scanOrchestrator.isScanRunning()).thenReturn(false);
        when(appSettingsService.updateSettings(any())).thenReturn(updated);

        mockMvc.perform(post("/api/v1/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervalMinutes\":45}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanIntervalMinutes").value(45));

        ArgumentCaptor<com.tradebot.dto.SettingsUpdateRequestDTO> captor = ArgumentCaptor.forClass(
                com.tradebot.dto.SettingsUpdateRequestDTO.class);
        verify(appSettingsService).updateSettings(captor.capture());
        assertEquals(Integer.valueOf(45), captor.getValue().getScanIntervalMinutes());
    }

    @Test
    void postAliasRiskMaxBudgetPct_mapsToMaxBudgetPct() throws Exception {
        AppSettings updated = buildSettings();
        updated.setMaxBudgetPct(new BigDecimal("4.25"));

        when(scanOrchestrator.isScanRunning()).thenReturn(false);
        when(appSettingsService.updateSettings(any())).thenReturn(updated);

        mockMvc.perform(post("/api/v1/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riskMaxBudgetPct\":4.25}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxBudgetPct").value(4.25));

        ArgumentCaptor<com.tradebot.dto.SettingsUpdateRequestDTO> captor = ArgumentCaptor.forClass(
                com.tradebot.dto.SettingsUpdateRequestDTO.class);
        verify(appSettingsService).updateSettings(captor.capture());
        assertEquals(new BigDecimal("4.25"), captor.getValue().getMaxBudgetPct());
    }

    @Test
    void postEquityOverrideNull_marksFieldProvidedForClearing() throws Exception {
        AppSettings updated = buildSettings();
        updated.setEquityOverrideUsdt(null);

        when(scanOrchestrator.isScanRunning()).thenReturn(false);
        when(appSettingsService.updateSettings(any())).thenReturn(updated);

        mockMvc.perform(post("/api/v1/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"equityOverrideUsdt\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equityOverrideUsdt").isEmpty());

        ArgumentCaptor<com.tradebot.dto.SettingsUpdateRequestDTO> captor = ArgumentCaptor.forClass(
                com.tradebot.dto.SettingsUpdateRequestDTO.class);
        verify(appSettingsService).updateSettings(captor.capture());
        assertTrue(captor.getValue().isEquityOverrideUsdtProvided());
        assertNull(captor.getValue().getEquityOverrideUsdt());
    }

    @Test
    void postInvalidInterval_returnsValidationFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scanIntervalMinutes\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION"))
                .andExpect(jsonPath("$.details.fieldErrors.scanIntervalMinutes")
                        .value("Scan interval must be at least 1 minute"));
    }

    @Test
    void postSchedulingChangeWhileScanRunning_returnsConflict() throws Exception {
        when(scanOrchestrator.isScanRunning()).thenReturn(true);

        mockMvc.perform(post("/api/v1/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"safeMode\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCAN_RUNNING"))
                .andExpect(jsonPath("$.message").value("Cannot change scheduling while scan is running."));
    }

    @Test
    void postRiskOnlyChangeWhileScanRunning_isAllowed() throws Exception {
        AppSettings updated = buildSettings();
        updated.setBudgetUsdt(new BigDecimal("25.0000"));

        when(scanOrchestrator.isScanRunning()).thenReturn(true);
        when(appSettingsService.updateSettings(any())).thenReturn(updated);

        mockMvc.perform(post("/api/v1/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"budgetUsdt\":25}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetUsdt").value(25));
    }

    private AppSettings buildSettings() {
        AppSettings settings = new AppSettings();
        settings.setSafeMode(false);
        settings.setSchedulerEnabled(true);
        settings.setScanIntervalMinutes(20);
        settings.setBudgetUsdt(new BigDecimal("10.0000"));
        settings.setMaxBudgetPct(new BigDecimal("5.00"));
        settings.setMaxEquityPct(new BigDecimal("1.00"));
        return settings;
    }
}
