package com.tradebot.operator;

import java.util.List;

public record OperatorPermissionResponseDTO(
        int version,
        List<OperatorPermissionItemDTO> items) {
}
