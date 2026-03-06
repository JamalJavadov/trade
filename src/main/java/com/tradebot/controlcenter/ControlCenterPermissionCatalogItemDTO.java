package com.tradebot.controlcenter;

public record ControlCenterPermissionCatalogItemDTO(
        String key,
        String title,
        String description,
        String group,
        String dangerLevel) {
}
