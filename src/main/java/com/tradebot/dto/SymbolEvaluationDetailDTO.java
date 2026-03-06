package com.tradebot.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class SymbolEvaluationDetailDTO extends SymbolEvaluationRowDTO {
    private Map<String, Object> metrics;
    private Map<String, Object> diagnostics;
}
