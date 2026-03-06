package com.tradebot.operator;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.tradebot.security.LocalMutationGuard;

import java.util.List;

@RestController
@RequestMapping("/api/v1/operator")
@RequiredArgsConstructor
public class OperatorPermissionController {

    private final OperatorPermissionService operatorPermissionService;
    private final LocalMutationGuard localMutationGuard;

    @GetMapping("/permissions")
    public ResponseEntity<List<OperatorPermissionItemDTO>> getPermissions() {
        return ResponseEntity.ok(operatorPermissionService.listPermissions());
    }

    @PostMapping("/permissions")
    public ResponseEntity<List<OperatorPermissionItemDTO>> updatePermissions(
            HttpServletRequest requestContext,
            @RequestHeader(name = "X-Operator-Id", required = false) String operatorId,
            @Valid @RequestBody OperatorPermissionUpdateRequestDTO request) {
        localMutationGuard.assertLocal(requestContext);
        return ResponseEntity.ok(operatorPermissionService.updatePermissions(request.updates(), operatorId));
    }
}
