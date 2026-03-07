package com.tradebot.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OperatorPermissionService {

    private static final Map<String, String> LEGACY_KEY_ALIASES = Map.of(
            "scan.autoscan.enable", "scan.autoscan.toggle",
            "ai.models.manage", "ai.models.update",
            "live.execution.view", "live.execution.enabled",
            "live.execution.run", "live.execution.enabled",
            "live.execution.reconcile", "live.execution.enabled");

    private final PermissionCatalog permissionCatalog;
    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<OperatorPermissionItemDTO> listPermissions() {
        ControlCenterConfig config = controlCenterSettingsProvider.getConfigSnapshot();
        LocalDateTime updatedAt = controlCenterSettingsProvider.getUpdatedAt() != null
                ? LocalDateTime.ofInstant(controlCenterSettingsProvider.getUpdatedAt(), ZoneOffset.UTC)
                : null;
        String updatedBy = controlCenterSettingsProvider.getCachedState().updatedBy();

        List<OperatorPermissionItemDTO> items = new ArrayList<>();
        Set<String> includedCanonical = new HashSet<>();

        for (PermissionCatalog.PermissionDefinition definition : permissionCatalog.all()) {
            String canonical = canonicalizeKey(definition.permissionKey());
            boolean enabled = config.getPermissions().getOrDefault(canonical, true);
            items.add(new OperatorPermissionItemDTO(
                    canonical,
                    definition.title(),
                    definition.description(),
                    definition.group().name(),
                    definition.dangerLevel().name(),
                    enabled,
                    updatedAt,
                    updatedBy));
            includedCanonical.add(canonical);
        }

        for (Map.Entry<String, Boolean> entry : config.getPermissions().entrySet()) {
            String canonical = canonicalizeKey(entry.getKey());
            if (includedCanonical.contains(canonical) || isLegacyAlias(entry.getKey())) {
                continue;
            }
            items.add(new OperatorPermissionItemDTO(
                    canonical,
                    canonical,
                    "",
                    "OTHER",
                    DangerLevel.LOW.name(),
                    Boolean.TRUE.equals(entry.getValue()),
                    updatedAt,
                    updatedBy));
            includedCanonical.add(canonical);
        }

        items.sort((left, right) -> {
            int byGroup = left.group().compareTo(right.group());
            if (byGroup != 0) {
                return byGroup;
            }
            return left.key().compareTo(right.key());
        });
        return items;
    }

    @Transactional
    public List<OperatorPermissionItemDTO> updatePermissions(List<OperatorPermissionUpdateDTO> updates, String updatedBy) {
        if (updates == null) {
            throw new IllegalArgumentException("updates must be provided");
        }

        ControlCenterConfig current = controlCenterSettingsProvider.getConfigSnapshot();
        Set<String> seen = new HashSet<>();
        for (OperatorPermissionUpdateDTO update : updates) {
            if (update.key() == null || update.key().isBlank()) {
                throw new IllegalArgumentException("Permission key must be provided");
            }
            String canonicalKey = canonicalizeKey(update.key());
            if (!seen.add(canonicalKey)) {
                throw new IllegalArgumentException("Duplicate permission key in updates: " + canonicalKey);
            }
            if (update.enabled() == null) {
                throw new IllegalArgumentException("enabled flag must be provided for key: " + canonicalKey);
            }
            if (!permissionCatalog.contains(canonicalKey) && !current.getPermissions().containsKey(canonicalKey)) {
                throw new IllegalArgumentException("Unknown permission key: " + canonicalKey);
            }
        }

        if (updates.isEmpty()) {
            return listPermissions();
        }

        ObjectNode patch = objectMapper.createObjectNode();
        ObjectNode permissions = patch.putObject("permissions");
        for (OperatorPermissionUpdateDTO update : updates) {
            permissions.put(canonicalizeKey(update.key()), Boolean.TRUE.equals(update.enabled()));
        }
        String actor = updatedBy == null || updatedBy.isBlank() ? "local-operator" : updatedBy.trim();
        controlCenterSettingsProvider.patch(patch, "legacy-permissions", actor);

        return listPermissions();
    }

    @Transactional(readOnly = true)
    public boolean isPermissionEnabled(String permissionKey) {
        return controlCenterSettingsProvider.can(canonicalizeKey(permissionKey));
    }

    @Transactional(readOnly = true)
    public void requirePermissionEnabled(String permissionKey) {
        String canonicalKey = canonicalizeKey(permissionKey);
        if (!controlCenterSettingsProvider.can(canonicalKey)) {
            throw new ForbiddenPermissionException(canonicalKey, resolveTitle(canonicalKey));
        }
    }

    @Transactional(readOnly = true)
    public String resolvePermissionTitle(String permissionKey) {
        String canonicalKey = canonicalizeKey(permissionKey);
        return resolveTitle(canonicalKey);
    }

    public String canonicalizeKey(String permissionKey) {
        if (permissionKey == null) {
            return null;
        }
        return LEGACY_KEY_ALIASES.getOrDefault(permissionKey, permissionKey);
    }

    public boolean isLegacyAlias(String permissionKey) {
        return LEGACY_KEY_ALIASES.containsKey(permissionKey);
    }

    private String resolveTitle(String canonicalKey) {
        return permissionCatalog.find(canonicalKey)
                .map(PermissionCatalog.PermissionDefinition::title)
                .orElse(canonicalKey);
    }
}
