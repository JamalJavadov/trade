export interface ScanPhaseDTO {
    name?: string;
    phase: string;
    status: string;
    startedAt: string;
    finishedAt: string | null;
    durationMs: number | null;
    meta: Record<string, unknown>;
}

export interface ScanSummaryDTO {
    id: string;
    startedAt: string;
    finishedAt: string | null;
    status: string;
    intervalMinutes: number;
    topN: number;
    bestRecommendationId: string | null;
    phases: ScanPhaseDTO[];
    evaluatedCount: number;
    validCount: number;
    noTradeCount: number;
    eligibleCount: number;
    blockedCount: number;
    conflictCount: number;
    dataIntegrityFailureCount: number;
    notes: string | null;
}

export interface SymbolEvaluationRowDTO {
    symbol: string;
    decision: string;
    side: string;
    bias: string | null;
    rankInUniverse: number | null;
    quoteVolumeUsdt: number | null;
    finalScore: number | null;
    rrTp1: number | null;
    confidence: number | null;
    rank?: number | null;
    confidenceScore?: number | null;
    entry: number | string | null;
    sl: number | string | null;
    tp1: number | string | null;
    skipReasonCode: string | null;
    skipReasonText: string | null;
    traceId?: string | null;
    recommendationEligible?: boolean | null;
    finalIntegrityScore?: number | null;
    conflictState?: string | null;
    aiAgreementState?: string | null;
    aiReviewStatus?: string | null;
    rejectionReasons?: string[] | null;
    latestCandidateStage?: string | null;
    latestCandidateStageStatus?: string | null;
    createdAt: string | null;
}

export interface SymbolEvaluationDetailDTO extends SymbolEvaluationRowDTO {
    metrics: Record<string, unknown> | null;
    diagnostics: Record<string, unknown> | null;
    snapshot?: Record<string, unknown> | null;
    integrity?: Record<string, unknown> | null;
    deterministicEvidence?: Record<string, unknown> | null;
    validation?: Record<string, unknown> | null;
    confirmation?: Record<string, unknown> | null;
    aiReview?: Record<string, unknown> | null;
    finalGate?: Record<string, unknown> | null;
    candidateEvents?: ScanCandidateEventDTO[] | null;
}

export interface ScanCandidateEventDTO {
    symbol: string;
    stage: string;
    seq: number;
    status: string;
    ts: string;
    payload: Record<string, unknown> | null;
}

export interface Page<T> {
    content: T[];
    totalElements: number;
    totalPages: number;
    size: number;
    number: number;
}

export interface ScatterPoint {
    symbol: string;
    rrTp1: number;
    confidence: number;
    finalScore: number;
}

export interface HistogramBin {
    bucketStart: number;
    bucketEnd: number;
    count: number;
    binLabel?: string;
}

export interface ScanChartsDTO {
    rrHist: HistogramBin[];
    confHist: HistogramBin[];
    scatterPoints: ScatterPoint[];
    skipReasonBreakdown: Record<string, number>;
    biasBreakdown: Record<string, number>;
}
