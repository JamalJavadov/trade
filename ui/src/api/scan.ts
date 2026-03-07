import { apiClient } from './axiosSetup';
import type { Page, ScanChartsDTO, ScanSummaryDTO, SymbolEvaluationDetailDTO, SymbolEvaluationRowDTO } from '../types/scan';
import type { ScanReplayDTO, ExplanationDTO } from '../types/scanReplay';

function normalizeSummary(raw: ScanSummaryDTO): ScanSummaryDTO {
    return {
        ...raw,
        phases: Array.isArray(raw.phases) ? raw.phases : [],
        eligibleCount: raw.eligibleCount ?? 0,
        blockedCount: raw.blockedCount ?? 0,
        conflictCount: raw.conflictCount ?? 0,
        dataIntegrityFailureCount: raw.dataIntegrityFailureCount ?? 0,
    };
}

function normalizeEvaluationRow(raw: any): SymbolEvaluationRowDTO {
    return {
        symbol: raw.symbol,
        decision: raw.decision,
        side: raw.side,
        bias: raw.bias ?? null,
        rankInUniverse: raw.rankInUniverse ?? raw.rank ?? null,
        quoteVolumeUsdt: raw.quoteVolumeUsdt ?? null,
        finalScore: raw.finalScore ?? null,
        rrTp1: raw.rrTp1 ?? null,
        confidence: raw.confidence ?? raw.confidenceScore ?? null,
        entry: raw.entry ?? null,
        sl: raw.sl ?? null,
        tp1: raw.tp1 ?? null,
        skipReasonCode: raw.skipReasonCode ?? null,
        skipReasonText: raw.skipReasonText ?? null,
        traceId: raw.traceId ?? null,
        recommendationEligible: raw.recommendationEligible ?? null,
        finalIntegrityScore: raw.finalIntegrityScore ?? null,
        conflictState: raw.conflictState ?? null,
        aiAgreementState: raw.aiAgreementState ?? null,
        aiReviewStatus: raw.aiReviewStatus ?? null,
        rejectionReasons: Array.isArray(raw.rejectionReasons) ? raw.rejectionReasons : [],
        latestCandidateStage: raw.latestCandidateStage ?? null,
        latestCandidateStageStatus: raw.latestCandidateStageStatus ?? null,
        createdAt: raw.createdAt ?? null,
    };
}

function normalizeCandidateEvent(raw: any) {
    return {
        symbol: raw?.symbol ?? '',
        stage: raw?.stage ?? '',
        seq: Number(raw?.seq ?? 0),
        status: raw?.status ?? '',
        ts: raw?.ts ?? '',
        payload: raw?.payload ?? null,
    };
}

function parseBinLabel(binLabel: string | undefined): { bucketStart: number; bucketEnd: number } | null {
    if (!binLabel || !binLabel.includes('-')) return null;
    const [startText, endText] = binLabel.split('-', 2);
    const start = Number(startText);
    const end = Number(endText);
    if (Number.isNaN(start) || Number.isNaN(end)) return null;
    return { bucketStart: start, bucketEnd: end };
}

function normalizeCharts(raw: any): ScanChartsDTO {
    const normalizeBin = (bin: any) => {
        const parsed = parseBinLabel(bin?.binLabel);
        return {
            bucketStart: Number(bin?.bucketStart ?? parsed?.bucketStart ?? 0),
            bucketEnd: Number(bin?.bucketEnd ?? parsed?.bucketEnd ?? 0),
            count: Number(bin?.count ?? 0),
        };
    };

    return {
        rrHist: Array.isArray(raw?.rrHist) ? raw.rrHist.map(normalizeBin) : [],
        confHist: Array.isArray(raw?.confHist) ? raw.confHist.map(normalizeBin) : [],
        scatterPoints: Array.isArray(raw?.scatterPoints) ? raw.scatterPoints : [],
        skipReasonBreakdown: raw?.skipReasonBreakdown ?? {},
        biasBreakdown: raw?.biasBreakdown ?? {},
    };
}

export const getLatestScan = async (): Promise<ScanSummaryDTO | null> => {
    const res = await apiClient.get<ScanSummaryDTO>('/api/v1/scans/latest');
    return res.status === 204 ? null : normalizeSummary(res.data);
};

export const getScan = async (scanRunId: string): Promise<ScanSummaryDTO> => {
    const res = await apiClient.get<ScanSummaryDTO>(`/api/v1/scans/${scanRunId}`);
    return normalizeSummary(res.data);
};

export const getScanEvaluations = async (
    scanRunId: string,
    params?: { decision?: string, q?: string, sort?: string, limit?: number, offset?: number }
): Promise<Page<SymbolEvaluationRowDTO>> => {
    const res = await apiClient.get<Page<SymbolEvaluationRowDTO>>(`/api/v1/scans/${scanRunId}/evaluations`, { params });
    return {
        ...res.data,
        content: Array.isArray(res.data.content) ? res.data.content.map(normalizeEvaluationRow) : []
    };
};

export const getScanEvaluationDetail = async (scanRunId: string, symbol: string): Promise<SymbolEvaluationDetailDTO> => {
    const res = await apiClient.get<SymbolEvaluationDetailDTO>(`/api/v1/scans/${scanRunId}/evaluations/${symbol}`);
    const row = normalizeEvaluationRow(res.data);
    return {
        ...row,
        metrics: (res.data as any).metrics ?? null,
        diagnostics: (res.data as any).diagnostics ?? null,
        snapshot: (res.data as any).snapshot ?? null,
        integrity: (res.data as any).integrity ?? null,
        deterministicEvidence: (res.data as any).deterministicEvidence ?? null,
        validation: (res.data as any).validation ?? null,
        confirmation: (res.data as any).confirmation ?? null,
        aiReview: (res.data as any).aiReview ?? null,
        finalGate: (res.data as any).finalGate ?? null,
        candidateEvents: Array.isArray((res.data as any).candidateEvents)
            ? (res.data as any).candidateEvents.map(normalizeCandidateEvent)
            : [],
    };
};

export const getScanCharts = async (scanRunId: string): Promise<ScanChartsDTO> => {
    const res = await apiClient.get<ScanChartsDTO>(`/api/v1/scans/${scanRunId}/charts`);
    return normalizeCharts(res.data);
};

export const getHistoricalScans = async (limit: number = 20, offset: number = 0): Promise<Page<ScanSummaryDTO>> => {
    const res = await apiClient.get<Page<ScanSummaryDTO>>('/api/v1/scans', { params: { limit, offset } });
    return res.data;
};

export const getReplay = async (scanRunId: string): Promise<ScanReplayDTO> => {
    const res = await apiClient.get<ScanReplayDTO>(`/api/v1/scans/${scanRunId}/replay`);
    return {
        ...res.data,
        summary: normalizeSummary(res.data.summary),
        evaluations: Array.isArray(res.data.evaluations) ? res.data.evaluations.map(normalizeEvaluationRow) : [],
        candidateEvents: Array.isArray((res.data as any).candidateEvents)
            ? (res.data as any).candidateEvents.map(normalizeCandidateEvent)
            : [],
        charts: normalizeCharts(res.data.charts),
    };
};

export const getExplanation = async (scanRunId: string, symbol: string): Promise<ExplanationDTO> => {
    const res = await apiClient.get<ExplanationDTO>(`/api/v1/scans/${scanRunId}/evaluations/${symbol}/explain`);
    return res.data;
};
