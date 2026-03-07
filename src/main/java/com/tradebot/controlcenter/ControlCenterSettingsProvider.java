package com.tradebot.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiTaskType;
import com.tradebot.demo.service.DemoTradingLifecycleService;
import com.tradebot.dto.SettingsUpdateRequestDTO;
import com.tradebot.entity.AppSettings;
import com.tradebot.entity.ControlCenterStateEntity;
import com.tradebot.operator.OperatorPermissionEntity;
import com.tradebot.operator.OperatorPermissionRepository;
import com.tradebot.operator.PermissionCatalog;
import com.tradebot.repository.AppSettingsRepository;
import com.tradebot.repository.ControlCenterStateRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ControlCenterSettingsProvider implements PermissionProvider,
        ScanSettingsProvider,
        RiskSettingsProvider,
        AiRoutingProvider,
        DemoSettingsProvider {

    private static final int STATE_ID = 1;
    private static final BigDecimal MIN_BUDGET_PCT = new BigDecimal("0.1");
    private static final BigDecimal MAX_BUDGET_PCT = new BigDecimal("20.0");
    private static final BigDecimal LOCKED_EQUITY_CAP = BigDecimal.ONE;
    private static final BigDecimal LOCKED_MIN_RR = new BigDecimal("2.0");
    private static final Duration DB_LOAD_RETRY_BACKOFF = Duration.ofSeconds(30);
    private static final String LIVE_EXECUTION_PERMISSION = "live.execution.enabled";
    private static final List<String> LEGACY_LIVE_EXECUTION_PERMISSIONS = List.of(
            "live.execution.view",
            "live.execution.run",
            "live.execution.reconcile");

    private static final List<String> DEFAULT_ALLOWLIST = List.of(
            "stepfun/step-3.5-flash:free",
            "z-ai/glm-4.5-air:free",
            "nvidia/nemotron-3-nano-30b-a3b:free",
            "nvidia/nemotron-nano-9b-v2:free",
            "qwen/qwen3-vl-235b-a22b-thinking",
            "qwen/qwen3-vl-30b-a3b-thinking",
            "openai/gpt-oss-120b:free");

    private final ControlCenterStateRepository controlCenterStateRepository;
    private final AppSettingsRepository appSettingsRepository;
    private final ObjectProvider<OperatorPermissionRepository> operatorPermissionRepositoryProvider;
    private final PermissionCatalog permissionCatalog;
    private final ObjectProvider<DemoTradingLifecycleService> demoTradingLifecycleServiceProvider;
    private final ObjectMapper objectMapper;
    private final ControlCenterCache controlCenterCache;
    private volatile Instant nextDbLoadAttemptAt = Instant.EPOCH;

    @PostConstruct
    public void bootstrapOnStartup() {
        try {
            getCachedState();
        } catch (Exception ex) {
            log.warn("Control-center bootstrap deferred due to startup error: {}", ex.getMessage());
        }
    }

    public ControlCenterCache.CachedState getCachedState() {
        return controlCenterCache.get(this::loadStateFromDb);
    }

    public ControlCenterConfig getConfigSnapshot() {
        return deepCopy(getCachedState().config());
    }

    public int getCurrentVersion() {
        return getCachedState().version();
    }

    public Instant getUpdatedAt() {
        return getCachedState().updatedAt();
    }

    @Override
    public boolean can(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        ControlCenterConfig config = getConfigSnapshot();
        Boolean enabled = config.getPermissions().get(key);
        return enabled == null || enabled;
    }

    @Override
    public ControlCenterConfig.Scan getScanSettings() {
        return deepCopy(getConfigSnapshot().getScan());
    }

    @Override
    public ControlCenterConfig.Risk getRiskSettings() {
        return deepCopy(getConfigSnapshot().getRisk());
    }

    @Override
    public ControlCenterConfig.Mode getLiveRouting() {
        return deepCopy(getConfigSnapshot().getAi().getLive());
    }

    @Override
    public ControlCenterConfig.Mode getDemoRouting() {
        return deepCopy(getConfigSnapshot().getAi().getDemo());
    }

    @Override
    public ControlCenterConfig.DemoTrading getDemoSettings() {
        return deepCopy(getConfigSnapshot().getDemoTrading());
    }

    public ControlCenterConfig.LiveExecution getLiveExecutionSettings() {
        return deepCopy(getConfigSnapshot().getLiveExecution());
    }

    public boolean isLiveExecutionReadOnly() {
        return getConfigSnapshot().getLiveExecution().isReadOnly();
    }

    @Transactional
    public ControlCenterConfig patch(JsonNode patch, String reason, String actor) {
        if (patch != null && !patch.isObject()) {
            throw new IllegalArgumentException("patch must be a JSON object");
        }

        ControlCenterStateEntity entity = controlCenterStateRepository.findById(STATE_ID)
                .orElseGet(this::bootstrapStateEntity);
        ControlCenterConfig current = parseConfig(entity.getConfigJson());
        boolean currentChanged = migrateLegacyLiveExecutionState(current, entity.getConfigJson())
                || normalizeAndValidate(current)
                || hasLegacyLiveExecutionShape(entity.getConfigJson());

        ObjectNode root = objectMapper.valueToTree(current);
        if (patch != null) {
            mergeObject(root, patch);
        }

        ControlCenterConfig merged = objectMapper.convertValue(root, ControlCenterConfig.class);
        normalizeAndValidate(merged);
        if (objectMapper.valueToTree(current).equals(objectMapper.valueToTree(merged))) {
            if (currentChanged) {
                entity.setConfigJson(writeJson(merged));
                entity.setUpdatedAt(Instant.now());
                entity.setUpdatedBy(auditValue(actor, reason == null ? "runtime-normalize" : reason));
                entity.setVersion(Math.max(entity.getVersion(), 1) + 1);
                controlCenterStateRepository.save(entity);
                controlCenterCache.invalidate();
            }
            return deepCopy(current);
        }

        boolean demoEnabledChanged = current.getDemoTrading().isEnabled() != merged.getDemoTrading().isEnabled();

        entity.setConfigJson(writeJson(merged));
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(auditValue(actor, reason));
        entity.setVersion(Math.max(entity.getVersion(), 1) + 1);
        controlCenterStateRepository.save(entity);

        controlCenterCache.invalidate();

        if (demoEnabledChanged) {
            DemoTradingLifecycleService lifecycleService = demoTradingLifecycleServiceProvider.getIfAvailable();
            if (lifecycleService != null) {
                lifecycleService.syncRuntimeWithControlCenter();
            }
        }

        return deepCopy(merged);
    }

    @Transactional
    public ControlCenterConfig patchState(JsonNode settingsPatch, JsonNode aiRoutingPatch) {
        ObjectNode patch = objectMapper.createObjectNode();

        if (settingsPatch != null && !settingsPatch.isNull()) {
            if (!settingsPatch.isObject()) {
                throw new IllegalArgumentException("settingsPatch must be a JSON object");
            }
            mergeObject(patch, settingsPatch);

            // Backward compatibility: legacy budget section maps into risk.budgetUsdt.
            if (settingsPatch.has("budget")) {
                JsonNode budget = settingsPatch.path("budget");
                if (budget.isObject() && budget.has("usdt")) {
                    patch.with("risk").set("budgetUsdt", budget.get("usdt"));
                }
                patch.remove("budget");
            }
        }

        if (aiRoutingPatch != null && !aiRoutingPatch.isNull()) {
            if (!aiRoutingPatch.isObject()) {
                throw new IllegalArgumentException("aiRoutingPatch must be a JSON object");
            }
            mergeLegacyAiPatch(patch.with("ai"), aiRoutingPatch);
        }

        return patch(patch, null, "legacy-adapter");
    }

    public List<String> allowlist(AiMode mode) {
        ControlCenterConfig config = getConfigSnapshot();
        return new ArrayList<>(config.getAi().getAllowlist());
    }

    public boolean isAiEnabled(AiMode mode) {
        ControlCenterConfig config = getConfigSnapshot();
        if (!config.getAi().isEnabled()) {
            return false;
        }
        if (mode == AiMode.DEMO) {
            return config.getDemoTrading().isEnabled();
        }
        return true;
    }

    public ControlCenterConfig.TaskRouting taskRouting(AiMode mode, AiTaskType taskType) {
        ControlCenterConfig config = getConfigSnapshot();
        ControlCenterConfig.Mode modeRouting = mode == AiMode.DEMO ? config.getAi().getDemo()
                : config.getAi().getLive();
        ControlCenterConfig.Routing routing = modeRouting.getRouting();

        ControlCenterConfig.TaskRouting selected;
        if (taskType == AiTaskType.SUGGESTION_BATCH) {
            selected = routing.getSuggestion();
        } else if (taskType == AiTaskType.EXPLAINABILITY_TEXT) {
            selected = routing.getExplainability();
        } else if (taskType == AiTaskType.SCAN_REVIEW) {
            selected = routing.getScanReview();
        } else {
            selected = routing.getVision();
        }

        return deepCopy(selected);
    }

    public AppSettings getLegacySettings() {
        ControlCenterConfig config = getConfigSnapshot();
        AppSettings settings = new AppSettings();
        settings.setId("DEFAULT");
        settings.setSafeMode(config.getScan().isSafeMode());
        settings.setSchedulerEnabled(config.getScan().isAutoscanEnabled());
        settings.setScanIntervalMinutes(config.getScan().getIntervalMinutes());
        settings.setBudgetUsdt(config.getRisk().getBudgetUsdt());
        settings.setMaxBudgetPct(config.getRisk().getMaxBudgetPct());
        settings.setEquityOverrideUsdt(config.getRisk().getEquityOverrideUsdt());
        settings.setMaxEquityPct(config.getRisk().getMaxEquityPctLocked());
        settings.setConfigJson(writeJson(config));
        settings.setUpdatedAt(getCachedState().updatedAt());
        return settings;
    }

    @Transactional
    public AppSettings updateLegacySettings(SettingsUpdateRequestDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Settings update payload is required");
        }

        ObjectNode patch = objectMapper.createObjectNode();
        if (dto.getSchedulerEnabled() != null || dto.getSafeMode() != null || dto.getScanIntervalMinutes() != null) {
            ObjectNode scan = patch.putObject("scan");
            if (dto.getSchedulerEnabled() != null) {
                scan.put("autoscanEnabled", dto.getSchedulerEnabled());
            }
            if (dto.getSafeMode() != null) {
                scan.put("safeMode", dto.getSafeMode());
            }
            if (dto.getScanIntervalMinutes() != null) {
                scan.put("intervalMinutes", dto.getScanIntervalMinutes());
            }
        }

        if (dto.getBudgetUsdt() != null || dto.getMaxBudgetPct() != null || dto.isEquityOverrideUsdtProvided()) {
            ObjectNode risk = patch.putObject("risk");
            if (dto.getBudgetUsdt() != null) {
                risk.put("budgetUsdt", dto.getBudgetUsdt());
            }
            if (dto.getMaxBudgetPct() != null) {
                risk.put("maxBudgetPct", dto.getMaxBudgetPct());
            }
            if (dto.isEquityOverrideUsdtProvided()) {
                if (dto.getEquityOverrideUsdt() == null) {
                    risk.putNull("equityOverrideUsdt");
                } else {
                    risk.put("equityOverrideUsdt", dto.getEquityOverrideUsdt());
                }
            }
        }

        if (patch.isEmpty()) {
            return getLegacySettings();
        }

        patch(patch, null, "legacy-settings");
        return getLegacySettings();
    }

    @Transactional
    public void resetDefaults(String actor, String reason) {
        ControlCenterConfig defaults = defaultConfig();
        normalizeAndValidate(defaults);

        ControlCenterStateEntity entity = controlCenterStateRepository.findById(STATE_ID)
                .orElseGet(this::bootstrapStateEntity);
        entity.setConfigJson(writeJson(defaults));
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(auditValue(actor, reason));
        entity.setVersion(Math.max(entity.getVersion(), 1) + 1);
        controlCenterStateRepository.save(entity);
        controlCenterCache.invalidate();

        DemoTradingLifecycleService lifecycleService = demoTradingLifecycleServiceProvider.getIfAvailable();
        if (lifecycleService != null) {
            lifecycleService.syncRuntimeWithControlCenter();
        }
    }

    private String auditValue(String actor, String reason) {
        String base = (actor == null || actor.isBlank()) ? "local-operator" : actor.trim();
        if (reason == null || reason.isBlank()) {
            return base;
        }
        return base + " | " + reason.trim();
    }

    private ControlCenterCache.CachedState loadStateFromDb() {
        Instant now = Instant.now();
        if (nextDbLoadAttemptAt.isAfter(now)) {
            return fallbackCachedState("fallback-db-backoff");
        }

        try {
            ControlCenterStateEntity entity = controlCenterStateRepository.findById(STATE_ID)
                    .orElseGet(this::bootstrapStateEntity);
            ControlCenterConfig config = parseConfig(entity.getConfigJson());

            boolean changed = migrateLegacyLiveExecutionState(config, entity.getConfigJson())
                    || normalizeAndValidate(config)
                    || hasLegacyLiveExecutionShape(entity.getConfigJson());
            if (changed) {
                entity.setConfigJson(writeJson(config));
                entity.setUpdatedAt(Instant.now());
                entity.setUpdatedBy(auditValue(entity.getUpdatedBy(), "runtime-normalize"));
                entity.setVersion(Math.max(entity.getVersion(), 1) + 1);
                controlCenterStateRepository.save(entity);
            }
            nextDbLoadAttemptAt = Instant.EPOCH;

            return new ControlCenterCache.CachedState(
                    deepCopy(config),
                    Math.max(entity.getVersion(), 1),
                    entity.getUpdatedAt(),
                    entity.getUpdatedBy());
        } catch (Throwable ex) {
            nextDbLoadAttemptAt = Instant.now().plus(DB_LOAD_RETRY_BACKOFF);
            log.error("Failed to load control center state from DB, falling back to defaults: {}", ex.getMessage(),
                    ex);
            return fallbackCachedState("fallback");
        }
    }

    private ControlCenterCache.CachedState fallbackCachedState(String updatedBy) {
        ControlCenterConfig defaults = defaultConfig();
        normalizeAndValidate(defaults);
        return new ControlCenterCache.CachedState(
                deepCopy(defaults),
                1,
                Instant.now(),
                updatedBy);
    }

    private ControlCenterStateEntity bootstrapStateEntity() {
        ControlCenterConfig config = migrateBestEffort(defaultConfig());
        normalizeAndValidate(config);

        ControlCenterStateEntity entity = new ControlCenterStateEntity();
        entity.setId(STATE_ID);
        entity.setConfigJson(writeJson(config));
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy("bootstrap");
        entity.setVersion(1);
        return controlCenterStateRepository.save(entity);
    }

    private ControlCenterConfig defaultConfig() {
        ControlCenterConfig config = new ControlCenterConfig();
        config.ensureDefaults();

        for (PermissionCatalog.PermissionDefinition definition : permissionCatalog.all()) {
            config.getPermissions().put(definition.permissionKey(), true);
        }

        config.getScan().setAutoscanEnabled(false);
        config.getScan().setIntervalMinutes(20);
        config.getScan().setSafeMode(false);

        config.getRisk().setBudgetUsdt(new BigDecimal("50"));
        config.getRisk().setMaxBudgetPct(new BigDecimal("5.0"));
        config.getRisk().setEquityOverrideUsdt(null);
        config.getRisk().setMaxEquityPctLocked(LOCKED_EQUITY_CAP);

        config.getAlerts().setEnabled(true);
        config.getAlerts().setVolume(new BigDecimal("0.9"));
        config.getAlerts().setDurationSeconds(10);

        config.getAi().setEnabled(true);
        config.getAi().setAllowlist(new ArrayList<>(DEFAULT_ALLOWLIST));

        config.getAi().getLive().getRouting().getSuggestion().setPrimaryModel(DEFAULT_ALLOWLIST.get(0));
        config.getAi().getLive().getRouting().getSuggestion().setFallbackModels(List.of(DEFAULT_ALLOWLIST.get(1)));
        config.getAi().getLive().getRouting().getExplainability().setPrimaryModel(DEFAULT_ALLOWLIST.get(0));
        config.getAi().getLive().getRouting().getExplainability().setFallbackModels(List.of(DEFAULT_ALLOWLIST.get(3)));
        config.getAi().getLive().getRouting().getVision().setPrimaryModel(DEFAULT_ALLOWLIST.get(5));
        config.getAi().getLive().getRouting().getVision().setFallbackModels(List.of(DEFAULT_ALLOWLIST.get(4)));
        config.getAi().getLive().getRouting().getScanReview().setPrimaryModel(DEFAULT_ALLOWLIST.get(0));
        config.getAi().getLive().getRouting().getScanReview()
                .setFallbackModels(List.of(DEFAULT_ALLOWLIST.get(1), DEFAULT_ALLOWLIST.get(3)));

        config.getAi().getDemo().getRouting().getSuggestion().setPrimaryModel(DEFAULT_ALLOWLIST.get(5));
        config.getAi().getDemo().getRouting().getSuggestion()
                .setFallbackModels(List.of(DEFAULT_ALLOWLIST.get(6), DEFAULT_ALLOWLIST.get(1)));
        config.getAi().getDemo().getRouting().getExplainability().setPrimaryModel(DEFAULT_ALLOWLIST.get(0));
        config.getAi().getDemo().getRouting().getExplainability()
                .setFallbackModels(List.of(DEFAULT_ALLOWLIST.get(3), DEFAULT_ALLOWLIST.get(1)));
        config.getAi().getDemo().getRouting().getVision().setPrimaryModel(DEFAULT_ALLOWLIST.get(5));
        config.getAi().getDemo().getRouting().getVision().setFallbackModels(List.of(DEFAULT_ALLOWLIST.get(4)));
        config.getAi().getDemo().getRouting().getScanReview().setPrimaryModel(DEFAULT_ALLOWLIST.get(5));
        config.getAi().getDemo().getRouting().getScanReview()
                .setFallbackModels(List.of(DEFAULT_ALLOWLIST.get(6), DEFAULT_ALLOWLIST.get(1)));

        config.getDemoTrading().setEnabled(false);
        config.getDemoTrading().setIntervalMinutes(15);
        config.getDemoTrading().setMaxOpenPositions(1);
        config.getDemoTrading().setStartBalanceUsdt(new BigDecimal("1000"));
        config.getDemoTrading().setRiskPct(new BigDecimal("0.5"));
        config.getDemoTrading().setLeverageDefault(5);
        config.getDemoTrading().setFeeBps(4);
        config.getDemoTrading().setSlippageBps(2);
        config.getDemoTrading().setTimeStopMinutes(90);

        config.getLiveExecution().setReadOnly(false);

        config.getStrategyLocks().setExecutionTf("15m");
        config.getStrategyLocks().setBiasTf("1h");
        config.getStrategyLocks().setFractalPeriod(5);
        config.getStrategyLocks().setMinRr(LOCKED_MIN_RR);

        return config;
    }

    private ControlCenterConfig migrateBestEffort(ControlCenterConfig defaults) {
        ControlCenterConfig migrated = deepCopy(defaults);

        AppSettings appSettings = appSettingsRepository.findById("DEFAULT").orElse(null);
        if (appSettings != null) {
            migrated.getScan().setSafeMode(appSettings.isSafeMode());
            migrated.getScan().setAutoscanEnabled(appSettings.isSchedulerEnabled());
            if (appSettings.getScanIntervalMinutes() > 0) {
                migrated.getScan().setIntervalMinutes(appSettings.getScanIntervalMinutes());
            }

            if (appSettings.getBudgetUsdt() != null && appSettings.getBudgetUsdt().compareTo(BigDecimal.ZERO) > 0) {
                migrated.getRisk().setBudgetUsdt(appSettings.getBudgetUsdt());
            }
            if (appSettings.getMaxBudgetPct() != null) {
                migrated.getRisk().setMaxBudgetPct(appSettings.getMaxBudgetPct());
            }
            migrated.getRisk().setEquityOverrideUsdt(appSettings.getEquityOverrideUsdt());

            if (appSettings.getConfigJson() != null && !appSettings.getConfigJson().isBlank()) {
                try {
                    JsonNode legacyRoot = objectMapper.readTree(appSettings.getConfigJson());
                    if (legacyRoot.has("permissions") && legacyRoot.path("permissions").isObject()) {
                        legacyRoot.path("permissions").fields().forEachRemaining(entry -> migrated.getPermissions()
                                .put(entry.getKey(), entry.getValue().asBoolean(true)));
                    }
                } catch (Exception ex) {
                    log.warn("Unable to parse legacy app_settings.config_json during bootstrap: {}", ex.getMessage());
                }
            }
        }

        OperatorPermissionRepository permissionRepository = operatorPermissionRepositoryProvider.getIfAvailable();
        if (permissionRepository != null) {
            List<OperatorPermissionEntity> rows = permissionRepository.findAllByOrderByGroupNameAscKeyAsc();
            for (OperatorPermissionEntity row : rows) {
                migrated.getPermissions().put(row.getKey(), row.isEnabled());
            }
        }

        return migrated;
    }

    private ControlCenterConfig parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return defaultConfig();
        }
        try {
            ControlCenterConfig config = objectMapper.readValue(json, ControlCenterConfig.class);
            if (config == null) {
                return defaultConfig();
            }
            config.ensureDefaults();
            return config;
        } catch (Throwable ex) {
            log.warn("Invalid control_center_state.config_json detected, falling back to defaults: {}",
                    ex.getMessage());
            return defaultConfig();
        }
    }

    private boolean normalizeAndValidate(ControlCenterConfig config) {
        config.ensureDefaults();
        boolean changed = false;
        ControlCenterConfig defaults = defaultConfig();
        changed = migrateLiveExecutionCapability(config.getPermissions()) || changed;

        // Ensure all catalog permissions are present.
        for (PermissionCatalog.PermissionDefinition definition : permissionCatalog.all()) {
            if (!config.getPermissions().containsKey(definition.permissionKey())) {
                config.getPermissions().put(definition.permissionKey(), true);
                changed = true;
            }
        }

        if (config.getScan().getIntervalMinutes() < 1) {
            config.getScan().setIntervalMinutes(1);
            changed = true;
        }

        if (config.getRisk().getBudgetUsdt() == null
                || config.getRisk().getBudgetUsdt().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("risk.budgetUsdt must be greater than 0");
        }

        if (config.getRisk().getMaxBudgetPct() == null
                || config.getRisk().getMaxBudgetPct().compareTo(MIN_BUDGET_PCT) < 0
                || config.getRisk().getMaxBudgetPct().compareTo(MAX_BUDGET_PCT) > 0) {
            throw new IllegalArgumentException("risk.maxBudgetPct must be between 0.1 and 20");
        }

        if (config.getRisk().getEquityOverrideUsdt() != null
                && config.getRisk().getEquityOverrideUsdt().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("risk.equityOverrideUsdt must be > 0 when provided");
        }

        if (config.getRisk().getMaxEquityPctLocked() == null
                || config.getRisk().getMaxEquityPctLocked().compareTo(LOCKED_EQUITY_CAP) != 0) {
            config.getRisk().setMaxEquityPctLocked(LOCKED_EQUITY_CAP);
            changed = true;
        }

        if (config.getAlerts().getVolume() == null
                || config.getAlerts().getVolume().compareTo(BigDecimal.ZERO) < 0
                || config.getAlerts().getVolume().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("alerts.volume must be between 0 and 1");
        }

        if (config.getDemoTrading().getIntervalMinutes() < 1) {
            throw new IllegalArgumentException("demoTrading.intervalMinutes must be at least 1");
        }
        if (config.getDemoTrading().getMaxOpenPositions() < 1 || config.getDemoTrading().getMaxOpenPositions() > 5) {
            throw new IllegalArgumentException("demoTrading.maxOpenPositions must be between 1 and 5");
        }
        if (config.getDemoTrading().getRiskPct() == null
                || config.getDemoTrading().getRiskPct().compareTo(BigDecimal.ZERO) < 0
                || config.getDemoTrading().getRiskPct().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("demoTrading.riskPct must be between 0 and 1");
        }

        if (config.getAi().getAllowlist().isEmpty()) {
            config.getAi().setAllowlist(new ArrayList<>(defaults.getAi().getAllowlist()));
            changed = true;
        }

        List<String> normalizedAllowlist = new ArrayList<>(
                new LinkedHashSet<>(trimModels(config.getAi().getAllowlist())));
        if (normalizedAllowlist.isEmpty()) {
            throw new IllegalArgumentException("ai.allowlist must not be empty");
        }

        changed = backfillRoutingDefaults(
                config.getAi().getLive().getRouting(),
                defaults.getAi().getLive().getRouting(),
                normalizedAllowlist)
                || changed;
        changed = backfillRoutingDefaults(
                config.getAi().getDemo().getRouting(),
                defaults.getAi().getDemo().getRouting(),
                normalizedAllowlist)
                || changed;

        if (!normalizedAllowlist.equals(config.getAi().getAllowlist())) {
            config.getAi().setAllowlist(normalizedAllowlist);
            changed = true;
        }

        validateRouting("ai.live.routing", config.getAi().getLive().getRouting(), normalizedAllowlist);
        validateRouting("ai.demo.routing", config.getAi().getDemo().getRouting(), normalizedAllowlist);

        if (!"15m".equalsIgnoreCase(config.getStrategyLocks().getExecutionTf())) {
            config.getStrategyLocks().setExecutionTf("15m");
            changed = true;
        }
        if (!"1h".equalsIgnoreCase(config.getStrategyLocks().getBiasTf())) {
            config.getStrategyLocks().setBiasTf("1h");
            changed = true;
        }
        if (config.getStrategyLocks().getFractalPeriod() != 5) {
            config.getStrategyLocks().setFractalPeriod(5);
            changed = true;
        }
        if (config.getStrategyLocks().getMinRr() == null
                || config.getStrategyLocks().getMinRr().compareTo(LOCKED_MIN_RR) < 0) {
            config.getStrategyLocks().setMinRr(LOCKED_MIN_RR);
            changed = true;
        }

        return changed;
    }

    private boolean migrateLiveExecutionCapability(Map<String, Boolean> permissions) {
        Boolean existingCanonical = permissions.get(LIVE_EXECUTION_PERMISSION);
        Boolean legacyRun = permissions.remove("live.execution.run");
        Boolean legacyView = permissions.remove("live.execution.view");
        Boolean legacyReconcile = permissions.remove("live.execution.reconcile");

        Boolean resolved = legacyRun != null
                ? legacyRun
                : existingCanonical != null
                        ? existingCanonical
                        : legacyView != null
                                ? legacyView
                                : legacyReconcile;
        if (resolved == null) {
            resolved = Boolean.TRUE;
        }

        boolean changed = legacyRun != null || legacyView != null || legacyReconcile != null;
        if (!resolved.equals(existingCanonical)) {
            permissions.put(LIVE_EXECUTION_PERMISSION, resolved);
            changed = true;
        } else if (!permissions.containsKey(LIVE_EXECUTION_PERMISSION)) {
            permissions.put(LIVE_EXECUTION_PERMISSION, resolved);
            changed = true;
        }
        return changed;
    }

    private boolean hasLegacyLiveExecutionShape(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) {
                return false;
            }
            if (root.has("liveTrading")) {
                return true;
            }
            JsonNode permissions = root.path("permissions");
            if (!permissions.isObject()) {
                return false;
            }
            for (String legacyKey : LEGACY_LIVE_EXECUTION_PERMISSIONS) {
                if (permissions.has(legacyKey)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean migrateLegacyLiveExecutionState(ControlCenterConfig config, String json) {
        if (config == null || json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) {
                return false;
            }

            JsonNode legacyLiveTrading = root.path("liveTrading");
            if (!legacyLiveTrading.isObject()) {
                return false;
            }

            boolean changed = false;
            JsonNode permissions = root.path("permissions");
            boolean hasCanonicalPermission = permissions.isObject() && permissions.has(LIVE_EXECUTION_PERMISSION);
            boolean hasLegacyPermission = permissions.isObject() && LEGACY_LIVE_EXECUTION_PERMISSIONS.stream()
                    .anyMatch(permissions::has);

            if (!hasCanonicalPermission && !hasLegacyPermission) {
                Boolean legacyCapability = booleanValue(legacyLiveTrading, "manualExecutionEnabled");
                if (legacyCapability == null) {
                    legacyCapability = booleanValue(legacyLiveTrading, "enabled");
                }
                if (legacyCapability != null
                        && !legacyCapability.equals(config.getPermissions().get(LIVE_EXECUTION_PERMISSION))) {
                    config.getPermissions().put(LIVE_EXECUTION_PERMISSION, legacyCapability);
                    changed = true;
                }
            }

            boolean readOnlyAlreadyPresent = root.path("liveExecution").isObject()
                    && root.path("liveExecution").has("readOnly");
            if (!readOnlyAlreadyPresent) {
                Boolean killSwitch = booleanValue(legacyLiveTrading, "killSwitch");
                Boolean armed = booleanValue(legacyLiveTrading, "armed");
                Boolean legacyReadOnly = killSwitch != null
                        ? killSwitch
                        : armed != null
                                ? !armed
                                : null;
                if (legacyReadOnly != null && config.getLiveExecution().isReadOnly() != legacyReadOnly) {
                    config.getLiveExecution().setReadOnly(legacyReadOnly);
                    changed = true;
                }
            }

            return changed;
        } catch (Exception ex) {
            return false;
        }
    }

    private Boolean booleanValue(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.has(field) || !node.get(field).isBoolean()) {
            return null;
        }
        return node.get(field).asBoolean();
    }

    private boolean backfillRoutingDefaults(
            ControlCenterConfig.Routing routing,
            ControlCenterConfig.Routing defaults,
            List<String> allowlist) {
        boolean changed = false;
        changed = backfillTaskDefaults(routing.getSuggestion(), defaults.getSuggestion(), allowlist) || changed;
        changed = backfillTaskDefaults(routing.getExplainability(), defaults.getExplainability(), allowlist) || changed;
        changed = backfillTaskDefaults(routing.getVision(), defaults.getVision(), allowlist) || changed;
        changed = backfillTaskDefaults(routing.getScanReview(), defaults.getScanReview(), allowlist) || changed;
        return changed;
    }

    private boolean backfillTaskDefaults(
            ControlCenterConfig.TaskRouting task,
            ControlCenterConfig.TaskRouting defaults,
            List<String> allowlist) {
        boolean changed = false;
        if (trimToNull(task.getPrimaryModel()) == null) {
            task.setPrimaryModel(defaults.getPrimaryModel());
            if (task.getFallbackModels() == null || task.getFallbackModels().isEmpty()) {
                task.setFallbackModels(new ArrayList<>(defaults.getFallbackModels()));
            }
            if (!allowlist.contains(defaults.getPrimaryModel())) {
                allowlist.add(defaults.getPrimaryModel());
            }
            for (String fallback : defaults.getFallbackModels()) {
                if (!allowlist.contains(fallback)) {
                    allowlist.add(fallback);
                }
            }
            changed = true;
        } else if (task.getFallbackModels() == null) {
            task.setFallbackModels(new ArrayList<>());
            changed = true;
        }
        return changed;
    }

    private void validateRouting(String path, ControlCenterConfig.Routing routing, List<String> allowlist) {
        validateTask(path + ".suggestion", routing.getSuggestion(), allowlist);
        validateTask(path + ".explainability", routing.getExplainability(), allowlist);
        validateTask(path + ".vision", routing.getVision(), allowlist);
        validateTask(path + ".scanReview", routing.getScanReview(), allowlist);
    }

    private void validateTask(String path, ControlCenterConfig.TaskRouting task, List<String> allowlist) {
        String primary = trimToNull(task.getPrimaryModel());
        if (primary == null) {
            throw new IllegalArgumentException(path + ".primaryModel is required");
        }
        if (!allowlist.contains(primary)) {
            throw new IllegalArgumentException(path + ".primaryModel must be allowlisted");
        }

        List<String> cleanFallbacks = new ArrayList<>();
        for (String fallback : task.getFallbackModels() == null ? List.<String>of() : task.getFallbackModels()) {
            String normalized = trimToNull(fallback);
            if (normalized == null) {
                throw new IllegalArgumentException(path + ".fallbackModels contains blank value");
            }
            if (!allowlist.contains(normalized)) {
                throw new IllegalArgumentException(path + ".fallbackModels contains non-allowlisted model");
            }
            if (primary.equals(normalized)) {
                throw new IllegalArgumentException(path + ".fallbackModels must not include primaryModel");
            }
            if (!cleanFallbacks.contains(normalized)) {
                cleanFallbacks.add(normalized);
            }
        }
        task.setPrimaryModel(primary);
        task.setFallbackModels(cleanFallbacks);
    }

    private void mergeLegacyAiPatch(ObjectNode aiPatch, JsonNode legacyPatch) {
        legacyPatch.fields().forEachRemaining(modeEntry -> {
            String modeKey = modeEntry.getKey();
            JsonNode modeValue = modeEntry.getValue();
            if (!modeValue.isObject()) {
                return;
            }

            ObjectNode targetMode = aiPatch.with(modeKey);
            ObjectNode routing = targetMode.with("routing");
            JsonNode tasks = modeValue.path("tasks");
            if (!tasks.isObject()) {
                return;
            }

            JsonNode suggestion = tasks.path(AiTaskType.SUGGESTION_BATCH.name());
            JsonNode explainability = tasks.path(AiTaskType.EXPLAINABILITY_TEXT.name());
            JsonNode vision = tasks.path(AiTaskType.VISION_DIAGNOSTIC.name());
            JsonNode scanReview = tasks.path(AiTaskType.SCAN_REVIEW.name());

            if (suggestion.isObject()) {
                routing.set("suggestion", suggestion);
            }
            if (explainability.isObject()) {
                routing.set("explainability", explainability);
            }
            if (vision.isObject()) {
                routing.set("vision", vision);
            }
            if (scanReview.isObject()) {
                routing.set("scanReview", scanReview);
            }
        });
    }

    private void mergeObject(ObjectNode target, JsonNode patch) {
        patch.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode patchValue = entry.getValue();
            JsonNode existingValue = target.get(key);

            if (patchValue != null && patchValue.isObject() && existingValue != null && existingValue.isObject()) {
                mergeObject((ObjectNode) existingValue, patchValue);
                return;
            }
            target.set(key, patchValue);
        });
    }

    private List<String> trimModels(List<String> models) {
        List<String> out = new ArrayList<>();
        if (models == null) {
            return out;
        }
        for (String model : models) {
            String normalized = trimToNull(model);
            if (normalized != null) {
                out.add(normalized);
            }
        }
        return out;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize control-center config", ex);
        }
    }

    private <T> T deepCopy(T value) {
        return objectMapper.convertValue(value, (Class<T>) value.getClass());
    }
}
