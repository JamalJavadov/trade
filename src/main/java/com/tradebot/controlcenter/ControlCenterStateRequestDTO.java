package com.tradebot.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ControlCenterStateRequestDTO {
    private JsonNode patch;
    private String reason;
}
