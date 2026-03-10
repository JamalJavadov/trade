package com.tradebot.operator;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class PermissionCatalog {

    private final List<PermissionDefinition> definitions;
    private final Map<String, PermissionDefinition> byKey;

    public PermissionCatalog() {
        this.definitions = List.of(
                new PermissionDefinition("scan.run_once", "Run Scan Once",
                        "Allows running a single immediate scan cycle from the UI.", PermissionGroup.SCAN, DangerLevel.MED),
                new PermissionDefinition("scan.autoscan.toggle", "Toggle Autoscan",
                        "Allows enabling or disabling the scheduled autoscan runtime.", PermissionGroup.SCAN,
                        DangerLevel.HIGH),
                new PermissionDefinition("scan.stream.view", "View Scan Stream",
                        "Allows viewing live scan stream events and progress.", PermissionGroup.SCAN, DangerLevel.LOW),

                new PermissionDefinition("settings.update", "Update Settings",
                        "Allows updating operational runtime settings via control APIs.", PermissionGroup.SETTINGS,
                        DangerLevel.HIGH),
                new PermissionDefinition("settings.risk_budget.update", "Update Risk/Budget",
                        "Allows updating budget and risk controls used by runtime sizing.", PermissionGroup.SETTINGS,
                        DangerLevel.HIGH),

                new PermissionDefinition("ai.suggestions.view", "View AI Suggestions",
                        "Allows viewing live AI suggestion batches and related metadata.", PermissionGroup.AI, DangerLevel.LOW),
                new PermissionDefinition("ai.suggestions.accept_reject", "Approve/Reject AI Suggestions",
                        "Allows accepting or rejecting live AI suggestion batches.", PermissionGroup.AI,
                        DangerLevel.HIGH),
                new PermissionDefinition("ai.models.update", "Update AI Models",
                        "Allows changing and testing AI routing allowlist and model chains.", PermissionGroup.AI,
                        DangerLevel.HIGH),

                new PermissionDefinition("live.execution.enabled", "Enable Live Execution",
                        "Allows manual Binance Futures order execution from recommendation detail and auto-session execution runtime.",
                        PermissionGroup.LIVE, DangerLevel.HIGH),
                new PermissionDefinition("live.execution.auto_session.audit.view", "View Auto Session Audit",
                        "Allows viewing budget-target auto-execution audit reports, timelines, trade history, and live audit stream.",
                        PermissionGroup.LIVE, DangerLevel.LOW),
                new PermissionDefinition("live.execution.auto_session.manage", "Manage Auto Session Execution",
                        "Allows enabling, disabling, and supervising budget-target auto-execution sessions.",
                        PermissionGroup.LIVE, DangerLevel.HIGH),

                new PermissionDefinition("journal.feedback.submit", "Submit Journal Feedback",
                        "Allows submitting trade outcome feedback entries.", PermissionGroup.JOURNAL,
                        DangerLevel.MED),

                new PermissionDefinition("errors.view", "View Errors",
                        "Allows viewing backend error center resources.", PermissionGroup.ERRORS,
                        DangerLevel.LOW),
                new PermissionDefinition("errors.clear", "Clear Errors",
                        "Allows clearing backend error center resources.", PermissionGroup.ERRORS,
                        DangerLevel.MED),

                new PermissionDefinition("exports.download", "Download Exports",
                        "Allows downloading analytics/journal export data.", PermissionGroup.EXPORTS,
                        DangerLevel.MED),

                new PermissionDefinition("demo.enable_disable", "Enable/Disable Demo",
                        "Allows enabling or disabling the demo trading runtime.", PermissionGroup.DEMO,
                        DangerLevel.HIGH),
                new PermissionDefinition("demo.run_once", "Run Demo Once",
                        "Allows running one manual demo cycle.", PermissionGroup.DEMO, DangerLevel.MED),
                new PermissionDefinition("demo.reset", "Reset Demo",
                        "Allows resetting demo trading state and historical demo data.", PermissionGroup.DEMO, DangerLevel.HIGH),
                new PermissionDefinition("demo.ai.accept_reject", "Approve/Reject Demo AI",
                        "Allows accepting or rejecting demo AI suggestion batches.", PermissionGroup.DEMO,
                        DangerLevel.HIGH));

        Map<String, PermissionDefinition> map = new LinkedHashMap<>();
        for (PermissionDefinition definition : definitions) {
            map.put(definition.permissionKey(), definition);
        }
        this.byKey = Map.copyOf(map);
    }

    public List<PermissionDefinition> all() {
        return definitions;
    }

    public Optional<PermissionDefinition> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public boolean contains(String key) {
        return byKey.containsKey(key);
    }

    public record PermissionDefinition(
            String permissionKey,
            String title,
            String description,
            PermissionGroup group,
            DangerLevel dangerLevel) {
    }
}
