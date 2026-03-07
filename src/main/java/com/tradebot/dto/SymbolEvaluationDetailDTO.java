package com.tradebot.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class SymbolEvaluationDetailDTO extends SymbolEvaluationRowDTO {
    private Map<String, Object> metrics;
    private Map<String, Object> diagnostics;
    private Map<String, Object> snapshot;
    private Map<String, Object> integrity;
    private Map<String, Object> deterministicEvidence;
    private Map<String, Object> validation;
    private Map<String, Object> confirmation;
    private Map<String, Object> aiReview;
    private Map<String, Object> finalGate;
    private java.util.List<ScanCandidateEventDTO> candidateEvents;
}
