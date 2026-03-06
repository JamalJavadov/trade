package com.tradebot;

import com.tradebot.controller.SettingsController;
import com.tradebot.dto.RiskPreviewRequestDTO;
import com.tradebot.dto.RiskPreviewResponseDTO;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.AppSettingsService;
import com.tradebot.service.ScanOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class SettingsControllerTest {

    private SettingsController controller;
    private AppSettingsService appSettingsService;
    private ScanOrchestrator scanOrchestrator;
    private OperatorPermissionService operatorPermissionService;
    private LocalMutationGuard localMutationGuard;

    @BeforeEach
    void setUp() {
        appSettingsService = mock(AppSettingsService.class);
        scanOrchestrator = mock(ScanOrchestrator.class);
        operatorPermissionService = mock(OperatorPermissionService.class);
        localMutationGuard = mock(LocalMutationGuard.class);
        controller = new SettingsController(appSettingsService, scanOrchestrator, operatorPermissionService, localMutationGuard);
    }

    @Test
    void testRiskPreview_BothConstraints() {
        RiskPreviewRequestDTO req = new RiskPreviewRequestDTO();
        req.setBudgetUsdt(new BigDecimal("1000"));
        req.setMaxBudgetPct(new BigDecimal("5.0"));

        req.setEquityOverrideUsdt(new BigDecimal("2000"));
        req.setMaxEquityPct(new BigDecimal("1.0")); // default

        ResponseEntity<RiskPreviewResponseDTO> resp = controller.previewRisk(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        RiskPreviewResponseDTO body = resp.getBody();
        assertNotNull(body);

        // Budget risk: 1000 * 5% = 50.0000
        assertEquals(0, new BigDecimal("50.0000").compareTo(body.getRiskUsdtFromBudget()));

        // Equity risk: 2000 * 1% = 20.0000
        assertEquals(0, new BigDecimal("20.0000").compareTo(body.getRiskUsdtFromEquity()));

        // Effective risk should be min(50, 20) = 20
        assertEquals(0, new BigDecimal("20.0000").compareTo(body.getEffectiveRiskUsdt()));

        assertTrue(body.getNotes().stream().anyMatch(n -> n.contains("effectiveRiskUsdt = min")));
    }

    @Test
    void testRiskPreview_OnlyBudget() {
        RiskPreviewRequestDTO req = new RiskPreviewRequestDTO();
        req.setBudgetUsdt(new BigDecimal("1000"));
        req.setMaxBudgetPct(new BigDecimal("5.0"));

        ResponseEntity<RiskPreviewResponseDTO> resp = controller.previewRisk(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        RiskPreviewResponseDTO body = resp.getBody();
        assertNotNull(body);

        assertEquals(0, new BigDecimal("50.0000").compareTo(body.getRiskUsdtFromBudget()));
        assertNull(body.getRiskUsdtFromEquity());
        assertEquals(0, new BigDecimal("50.0000").compareTo(body.getEffectiveRiskUsdt()));
    }
}
