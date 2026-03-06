package com.tradebot.controlcenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradebot.operator.RequiresPermission;
import com.tradebot.operator.PermissionCatalog;
import com.tradebot.security.LocalMutationGuard;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/control-center")
@RequiredArgsConstructor
public class ControlCenterController {

    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final PermissionCatalog permissionCatalog;
    private final LocalMutationGuard localMutationGuard;
    private final ObjectMapper objectMapper;

    @GetMapping("/state")
    public ResponseEntity<ControlCenterStateResponseDTO> getState() {
        return ResponseEntity.ok(buildState());
    }

    @PostMapping("/state")
    @RequiresPermission("settings.update")
    public ResponseEntity<ControlCenterStateResponseDTO> updateState(
            @RequestBody(required = false) ControlCenterStateRequestDTO request,
            @RequestHeader(name = "X-Operator-Id", required = false) String operatorId,
            HttpServletRequest httpServletRequest) {
        localMutationGuard.assertLocal(httpServletRequest);

        ControlCenterStateRequestDTO payload = request == null ? new ControlCenterStateRequestDTO() : request;
        ObjectNode patch = payload.getPatch() == null
                ? objectMapper.createObjectNode()
                : payload.getPatch().deepCopy();

        controlCenterSettingsProvider.patch(patch, payload.getReason(), operatorId);

        return ResponseEntity.ok(buildState());
    }

    private ControlCenterStateResponseDTO buildState() {
        List<ControlCenterPermissionCatalogItemDTO> catalog = permissionCatalog.all().stream()
                .map(definition -> new ControlCenterPermissionCatalogItemDTO(
                        definition.permissionKey(),
                        definition.title(),
                        definition.description(),
                        definition.group().name(),
                        definition.dangerLevel().name()))
                .toList();

        return new ControlCenterStateResponseDTO(
                controlCenterSettingsProvider.getConfigSnapshot(),
                Instant.now(),
                controlCenterSettingsProvider.getCurrentVersion(),
                catalog);
    }
}
