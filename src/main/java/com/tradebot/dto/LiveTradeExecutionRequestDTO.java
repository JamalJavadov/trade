package com.tradebot.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class LiveTradeExecutionRequestDTO {
    @NotNull(message = "clientRequestId is required")
    private UUID clientRequestId;

    @Size(max = 1000, message = "operatorNote must be 1000 characters or fewer")
    private String operatorNote;
}
