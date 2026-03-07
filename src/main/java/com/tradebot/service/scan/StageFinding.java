package com.tradebot.service.scan;

import java.util.Map;

public record StageFinding(
        String code,
        String severity,
        String message,
        Map<String, Object> details) {
}
