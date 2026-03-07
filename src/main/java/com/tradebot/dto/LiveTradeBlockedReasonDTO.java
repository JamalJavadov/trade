package com.tradebot.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class LiveTradeBlockedReasonDTO {
    private String code;
    private String message;
    private String source;
    private Map<String, Object> details = new LinkedHashMap<>();

    public LiveTradeBlockedReasonDTO(String code, String message) {
        this(code, message, null, Map.of());
    }

    public LiveTradeBlockedReasonDTO(String code, String message, String source, Map<String, Object> details) {
        this.code = code;
        this.message = message;
        this.source = source;
        if (details != null) {
            this.details.putAll(details);
        }
    }
}
