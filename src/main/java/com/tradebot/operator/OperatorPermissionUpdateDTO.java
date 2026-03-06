package com.tradebot.operator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OperatorPermissionUpdateDTO(
        @NotBlank(message = "Permission key must be provided")
        String key,
        @NotNull(message = "enabled must be provided")
        Boolean enabled) {
}
