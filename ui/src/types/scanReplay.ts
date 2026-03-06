import type { ScanSummaryDTO, ScanPhaseDTO, SymbolEvaluationRowDTO, ScanChartsDTO } from './scan';

export interface ExplanationDTO {
    headline: string;
    bullets: string[];
    tags: string[];
}

export interface BestCandidateEventDTO {
    id: string;
    scanRunId: string;
    ts: string;
    symbol: string;
    side: string;
    finalScore: number;
    recommendationId: string | null;
    reasonJson: string;
}

export interface ScanReplayDTO {
    summary: ScanSummaryDTO;
    phases: ScanPhaseDTO[];
    bestCandidateEvents: BestCandidateEventDTO[];
    evaluations: SymbolEvaluationRowDTO[];
    charts: ScanChartsDTO;
}
