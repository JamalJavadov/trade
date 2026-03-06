package com.tradebot.service;

import com.tradebot.dto.AiDiagnosticsResponseDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AiDiagnosticsSanitizer {

    private static final int MAX_DEPTH = 6;
    private static final int DEFAULT_TEXT_LIMIT = 20_000;
    private static final int KEY_LIMIT = 128;

    public String sanitizeText(String raw) {
        return sanitizeText(raw, DEFAULT_TEXT_LIMIT);
    }

    public String sanitizeText(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }

        String sanitized = raw
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("(?i)\\bBearer\\s+[A-Za-z0-9._\\-]+", "Bearer ***")
                .replaceAll("sk-[A-Za-z0-9_-]+", "***")
                .replaceAll(
                        "(?i)(\\b(?:api[_-]?key|secret|password|token|authorization|bearer)\\b\\s*[:=]\\s*)([^\\s,;\\\"']+)",
                        "$1***");

        sanitized = sanitized.trim();
        if (maxChars > 0 && sanitized.length() > maxChars) {
            return sanitized.substring(0, maxChars);
        }
        return sanitized;
    }

    public Map<String, Object> sanitizeConstraints(Map<String, Object> constraints) {
        if (constraints == null) {
            return Map.of();
        }
        Object sanitized = sanitizeAny(constraints, DEFAULT_TEXT_LIMIT, 0);
        if (sanitized instanceof Map<?, ?> map) {
            Map<String, Object> casted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                casted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return casted;
        }
        return Map.of();
    }

    public Object sanitizeAny(Object value, int maxChars, int depth) {
        if (value == null) {
            return null;
        }
        if (depth >= MAX_DEPTH) {
            return "[truncated]";
        }
        if (value instanceof String text) {
            return sanitizeText(text, maxChars);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = sanitizeText(String.valueOf(entry.getKey()), KEY_LIMIT);
                if (isSensitiveKey(key)) {
                    sanitized.put(key, "***");
                } else {
                    sanitized.put(key, sanitizeAny(entry.getValue(), maxChars, depth + 1));
                }
            }
            return sanitized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : collection) {
                sanitized.add(sanitizeAny(item, maxChars, depth + 1));
            }
            return sanitized;
        }
        return sanitizeText(String.valueOf(value), maxChars);
    }

    public AiDiagnosticsResponseDTO sanitizeResponse(AiDiagnosticsResponseDTO raw) {
        if (raw == null) {
            return AiDiagnosticsResponseDTO.aiUnavailable();
        }

        List<AiDiagnosticsResponseDTO.CheckItemDTO> checks = new ArrayList<>();
        if (raw.getChecks() != null) {
            for (AiDiagnosticsResponseDTO.CheckItemDTO check : raw.getChecks()) {
                if (check == null) {
                    continue;
                }
                checks.add(new AiDiagnosticsResponseDTO.CheckItemDTO(
                        sanitizeText(check.getStep(), 600),
                        sanitizeText(check.getWhy(), 1200),
                        sanitizeText(check.getExpected(), 800)));
            }
        }

        List<AiDiagnosticsResponseDTO.FixItemDTO> fixes = new ArrayList<>();
        if (raw.getFixes() != null) {
            for (AiDiagnosticsResponseDTO.FixItemDTO fix : raw.getFixes()) {
                if (fix == null) {
                    continue;
                }
                fixes.add(new AiDiagnosticsResponseDTO.FixItemDTO(
                        sanitizeText(fix.getFix(), 900),
                        normalizeRisk(fix.getRisk())));
            }
        }

        AiDiagnosticsResponseDTO sanitized = new AiDiagnosticsResponseDTO();
        sanitized.setSummary(sanitizeText(raw.getSummary(), 500));
        sanitized.setProbableRootCause(sanitizeText(raw.getProbableRootCause(), 700));
        sanitized.setChecks(checks);
        sanitized.setFixes(fixes);
        return sanitized;
    }

    public String normalizeRisk(String risk) {
        if (risk == null) {
            return "med";
        }
        String normalized = risk.trim().toLowerCase(Locale.ROOT);
        if ("medium".equals(normalized)) {
            return "med";
        }
        if ("low".equals(normalized) || "med".equals(normalized) || "high".equals(normalized)) {
            return normalized;
        }
        return "med";
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("bearer");
    }
}
