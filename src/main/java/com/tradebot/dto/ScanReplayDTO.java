package com.tradebot.dto;

import lombok.Data;
import java.util.List;

@Data
public class ScanReplayDTO {
    private ScanSummaryDTO summary;
    private List<ScanPhaseDTO> phases;
    private List<BestCandidateEventDTO> bestCandidateEvents;
    private List<SymbolEvaluationRowDTO> evaluations;
    private List<ScanCandidateEventDTO> candidateEvents;
    private ScanChartsDTO charts;
}
