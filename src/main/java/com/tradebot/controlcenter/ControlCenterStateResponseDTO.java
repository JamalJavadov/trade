package com.tradebot.controlcenter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ControlCenterStateResponseDTO {
    private ControlCenterConfig config;
    private Instant serverTime;
    private int version;
    private List<ControlCenterPermissionCatalogItemDTO> permissionCatalog;
}
