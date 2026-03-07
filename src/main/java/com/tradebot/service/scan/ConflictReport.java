package com.tradebot.service.scan;

import java.util.List;
import java.util.Map;

public record ConflictReport(
        String state,
        boolean blocking,
        List<String> disagreements,
        Map<String, Object> comparedFields,
        String summary) {

    public static ConflictReport none() {
        return new ConflictReport("NONE", false, List.of(), Map.of(), "No confirmation conflicts detected.");
    }
}
