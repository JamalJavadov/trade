package com.tradebot.operator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record OperatorPermissionUpdateRequestDTO(
        @NotNull(message = "updates must be provided")
        List<@Valid OperatorPermissionUpdateDTO> updates) {
}
