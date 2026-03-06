package com.tradebot.operator;

import java.time.LocalDateTime;

public record OperatorPermissionItemDTO(
        String key,
        String title,
        String description,
        String group,
        String dangerLevel,
        boolean enabled,
        LocalDateTime updatedAt,
        String updatedBy) {

    public static OperatorPermissionItemDTO fromEntity(OperatorPermissionEntity entity) {
        return new OperatorPermissionItemDTO(
                entity.getKey(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getGroupName(),
                entity.getDangerLevel(),
                entity.isEnabled(),
                entity.getUpdatedAt(),
                entity.getUpdatedBy());
    }

    public static OperatorPermissionItemDTO fromDefinition(PermissionCatalog.PermissionDefinition definition) {
        return new OperatorPermissionItemDTO(
                definition.permissionKey(),
                definition.title(),
                definition.description(),
                definition.group().name(),
                definition.dangerLevel().name(),
                true,
                null,
                null);
    }
}
