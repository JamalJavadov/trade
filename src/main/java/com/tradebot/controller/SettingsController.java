package com.tradebot.controller;

import com.tradebot.entity.AppSettings;
import com.tradebot.dto.SettingsUpdateRequestDTO;
import com.tradebot.dto.SettingsDTO;
import com.tradebot.dto.RiskPreviewRequestDTO;
import com.tradebot.dto.RiskPreviewResponseDTO;
import com.tradebot.exception.ScanRunningException;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.operator.RequiresPermission;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.AppSettingsService;
import com.tradebot.service.ScanOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final AppSettingsService appSettingsService;
    private final ScanOrchestrator scanOrchestrator;
    private final OperatorPermissionService operatorPermissionService;
    private final LocalMutationGuard localMutationGuard;

    @GetMapping
    public ResponseEntity<SettingsDTO> getSettings() {
        AppSettings settings = appSettingsService.getSettings();
        return ResponseEntity.ok(toSettingsDTO(settings));
    }

    @PostMapping
    @RequiresPermission("settings.update")
    public ResponseEntity<SettingsDTO> updateSettings(
            @Valid @RequestBody SettingsUpdateRequestDTO updateRequest,
            HttpServletRequest request) {
        localMutationGuard.assertLocal(request);
        if (updateRequest.hasSchedulingChanges()) {
            operatorPermissionService.requirePermissionEnabled("scan.autoscan.toggle");
        }
        if (updateRequest.hasRiskBudgetChanges()) {
            operatorPermissionService.requirePermissionEnabled("settings.risk_budget.update");
        }

        if (updateRequest.hasSchedulingChanges() && scanOrchestrator.isScanRunning()) {
            throw new ScanRunningException("Cannot change scheduling while scan is running.");
        }

        AppSettings updated = appSettingsService.updateSettings(updateRequest);
        return ResponseEntity.ok(toSettingsDTO(updated));
    }

    private SettingsDTO toSettingsDTO(AppSettings settings) {
        SettingsDTO dto = new SettingsDTO();
        dto.setSafeMode(settings.isSafeMode());
        dto.setSchedulerEnabled(settings.isSchedulerEnabled());
        dto.setScanIntervalMinutes(settings.getScanIntervalMinutes());
        dto.setBudgetUsdt(settings.getBudgetUsdt());
        dto.setMaxBudgetPct(settings.getMaxBudgetPct());
        dto.setEquityOverrideUsdt(settings.getEquityOverrideUsdt());
        dto.setMaxEquityPct(settings.getMaxEquityPct());
        return dto;
    }

    @PostMapping("/risk-preview")
    @RequiresPermission("settings.risk_budget.update")
    public ResponseEntity<RiskPreviewResponseDTO> previewRisk(@Valid @RequestBody RiskPreviewRequestDTO request) {
        RiskPreviewResponseDTO response = new RiskPreviewResponseDTO();
        List<String> notes = new ArrayList<>();

        BigDecimal riskFromEquity = null;
        if (request.getEquityOverrideUsdt() != null && request.getMaxEquityPct() != null) {
            riskFromEquity = request.getEquityOverrideUsdt()
                    .multiply(request.getMaxEquityPct())
                    .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
            notes.add(String.format("Calculated risk from equity: %s * %s%% = %s",
                    request.getEquityOverrideUsdt(), request.getMaxEquityPct(), riskFromEquity));
        }

        BigDecimal riskFromBudget = null;
        if (request.getBudgetUsdt() != null && request.getMaxBudgetPct() != null) {
            riskFromBudget = request.getBudgetUsdt()
                    .multiply(request.getMaxBudgetPct())
                    .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
            notes.add(String.format("Calculated risk from budget: %s * %s%% = %s",
                    request.getBudgetUsdt(), request.getMaxBudgetPct(), riskFromBudget));
        }

        BigDecimal effectiveRisk = null;
        if (riskFromEquity != null && riskFromBudget != null) {
            effectiveRisk = riskFromEquity.min(riskFromBudget);
            notes.add(String.format("effectiveRiskUsdt = min(equityRisk, budgetRisk) = %s", effectiveRisk));
        } else if (riskFromEquity != null) {
            effectiveRisk = riskFromEquity;
            notes.add("Only equity limits provided. Using equityRisk constraint.");
        } else if (riskFromBudget != null) {
            effectiveRisk = riskFromBudget;
            notes.add("Only budget limits provided. Using budgetRisk constraint.");
        } else {
            notes.add("No budget or equity settings provided. Risk is zero.");
            effectiveRisk = BigDecimal.ZERO;
        }

        response.setRiskUsdtFromEquity(riskFromEquity);
        response.setRiskUsdtFromBudget(riskFromBudget);
        response.setEffectiveRiskUsdt(effectiveRisk);
        response.setNotes(notes);

        return ResponseEntity.ok(response);
    }
}
