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
    createdAt: string;
}

export interface SymbolEvaluationDetailDTO extends SymbolEvaluationRowDTO {
    metrics: Record<string, unknown> | null;
    diagnostics: Record<string, unknown> | null;
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
