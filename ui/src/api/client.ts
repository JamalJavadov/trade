import { apiClient } from './axiosSetup';

export interface StatusResponse {
    botTime: string;
    uptimeSeconds: number;
    lastScanTime: string | null;
    nextScanTime: string | null;
    latestRecommendationId: string | null;
}

export interface BinanceOrderFieldsDTO {
    symbol: string;
    side: string;
    type: string;
    quantity?: number | string | null;
    price?: number | string | null;
    stopPrice?: number | string | null;
    closePosition?: boolean | null;
    reduceOnly?: boolean | null;
    workingType?: string | null;
    positionSide?: string | null;
    timeInForce?: string | null;
}

export interface RecommendationDTO {
    id: string;
    scanRunId: string;
    symbol: string;
    side: string;
    rationaleText: string;
    confidenceScore: number;
    createdAt: string;
    status: string;
    entryOrder: BinanceOrderFieldsDTO;
    slOrder: BinanceOrderFieldsDTO;
    tpOrder: BinanceOrderFieldsDTO;
    leverageRecommendation: number;
    positionMode: string;
    marginMode: string;
    diagnosticsJson?: string;
    warning?: string | null;
}

export interface RecommendationPlaceabilityDTO {
    recommendationId: string;
    symbol: string | null;
    side: 'LONG' | 'SHORT' | null;
    markPrice: number | string | null;
    tickSize: number | string | null;
    placeable: boolean;
    reasonCode: string;
    reasonText: string;
    rules: {
        inequalityRule: string | null;
        minTickGap: number;
    };
    checks: {
        tpOk: boolean;
        slOk: boolean;
        rrOk: boolean;
    };
    computed: {
        entryRef: 'LIVE_MARK' | string;
        rrToTp1: number | string | null;
        tp1: number | string | null;
        sl: number | string | null;
        suggestedTp1Adjusted?: number | string | null;
        suggestedSlAdjusted?: number | string | null;
    };

    // Compatibility fields used by existing screens.
    tpRaw?: number | string | null;
    slRaw?: number | string | null;
    tpDisplay?: number | string | null;
    slDisplay?: number | string | null;
    ruleText?: string | null;
    requiredInequality?: string | null;
    liveRrToTp1?: number | string | null;
    minRrRequired?: number | string | null;
    manualPlacementAllowed: boolean;
    violations: string[];
    adjustments: string[];
    checkedAt: string;
}

export interface ScanRunStartResponseDTO {
    scanRunId?: string | null;
    status?: string | null;
}

export interface FeedbackRequest {
    userLabel: 'WIN' | 'LOSS';
    pnlUsdt?: number;
    rMultiple?: number;
    notes?: string;
}

export interface AiSuggestionItemDTO {
    id: string;
    key: string;
    proposedValue: string;
    reason: string;
    impactHypothesis: string;
    riskOfChange: string;
    status: string;
}

export interface AiSuggestionBatchDTO {
    id: string;
    status: string;
    createdAt: string;
    basedOnLastNTrades: number;
    summary: string;
    items: AiSuggestionItemDTO[];
}

export interface AiSuggestionLatestResponse {
    currentActiveConfigVersion: number;
    batch: AiSuggestionBatchDTO | null;
}

// Global App Settings / Risk Interfaces
export interface SettingsDTO {
    safeMode: boolean;
    schedulerEnabled: boolean;
    scanIntervalMinutes: number;
    budgetUsdt?: number | null;
    maxBudgetPct?: number | null;
    equityOverrideUsdt?: number | null;
    maxEquityPct?: number | null;
}

export type SettingsUpdatePayload = Partial<SettingsDTO> & {
    intervalMinutes?: number;
    riskMaxBudgetPct?: number;
};

export interface RiskPreviewRequestDTO {
    budgetUsdt?: number | null;
    maxBudgetPct?: number | null;
    equityOverrideUsdt?: number | null;
    maxEquityPct?: number | null;
}

export interface RiskPreviewResponseDTO {
    riskUsdtFromEquity: number | null;
    riskUsdtFromBudget: number | null;
    effectiveRiskUsdt: number | null;
    notes: string[];
}

export type AiTaskType = 'SUGGESTION_BATCH';

export interface AiModelLastCallDTO {
    status: string;
    traceId: string | null;
    latencyMs: number | null;
    modelUsed: string | null;
    calledAt: string | null;
}

export interface AiModelTaskDTO {
    taskType: AiTaskType;
    primaryModel: string;
    fallbackModels: string[];
    lastCall: AiModelLastCallDTO | null;
}

export interface AiModelsResponseDTO {
    mode: 'LIVE' | 'DEMO';
    controlsEnabled: boolean;
    allowlist: string[];
    tasks: AiModelTaskDTO[];
}

export interface AiModelsUpdateTaskDTO {
    taskType: AiTaskType;
    primaryModel: string;
    fallbackModels: string[];
}

export interface AiModelsUpdateRequestDTO {
    revertToDefaults: boolean;
    tasks: AiModelsUpdateTaskDTO[];
}

export interface AiModelsTestResponseDTO {
    mode: 'LIVE' | 'DEMO';
    taskType: AiTaskType;
    ok: boolean;
    simulated: boolean;
    traceId: string;
    latencyMs: number;
    modelUsed: string;
    payload: Record<string, unknown>;
}

export const getStatus = async (): Promise<StatusResponse> => {
    const res = await apiClient.get<StatusResponse>('/api/v1/status');
    return res.data;
};

export const runScanOnce = async (): Promise<ScanRunStartResponseDTO> => {
    const res = await apiClient.post<ScanRunStartResponseDTO>('/api/v1/scans/run-once');
    if (!res.data || typeof res.data !== 'object') {
        return {};
    }
    return res.data;
};

export const getLatestRecommendation = async (): Promise<RecommendationDTO | null> => {
    const res = await apiClient.get<RecommendationDTO>('/api/v1/recommendations/latest');
    return res.status === 204 ? null : res.data;
};

export const getRecommendation = async (id: string): Promise<RecommendationDTO> => {
    const res = await apiClient.get<RecommendationDTO>(`/api/v1/recommendations/${id}`);
    return res.data;
};

export const getRecommendationPlaceability = async (id: string): Promise<RecommendationPlaceabilityDTO> => {
    const res = await apiClient.get<RecommendationPlaceabilityDTO>(`/api/v1/recommendations/${id}/placeability`);
    return res.data;
};

export const submitFeedback = async (id: string, payload: FeedbackRequest): Promise<void> => {
    await apiClient.post(`/api/v1/recommendations/${id}/feedback`, payload);
};

function normalizeAiSuggestionLatestResponse(value: unknown): AiSuggestionLatestResponse {
    const raw = (value && typeof value === 'object') ? value as Record<string, unknown> : {};
    const rawBatch = raw.batch && typeof raw.batch === 'object' ? raw.batch as Record<string, unknown> : null;
    const rawItems = Array.isArray(rawBatch?.items)
        ? rawBatch.items
        : (Array.isArray(raw.items) ? raw.items : []);

    return {
        currentActiveConfigVersion: typeof raw.currentActiveConfigVersion === 'number'
            ? raw.currentActiveConfigVersion
            : 1,
        batch: rawBatch ? {
            id: String(rawBatch.id ?? ''),
            status: typeof rawBatch.status === 'string' ? rawBatch.status : 'UNKNOWN',
            createdAt: typeof rawBatch.createdAt === 'string' ? rawBatch.createdAt : new Date().toISOString(),
            basedOnLastNTrades: typeof rawBatch.basedOnLastNTrades === 'number' ? rawBatch.basedOnLastNTrades : 0,
            summary: typeof rawBatch.summary === 'string' ? rawBatch.summary : '',
            items: rawItems
                .filter((item): item is Record<string, unknown> => typeof item === 'object' && item !== null)
                .map((item) => ({
                    id: String(item.id ?? `${rawBatch.id ?? 'batch'}:${String(item.key ?? 'item')}`),
                    key: String(item.key ?? ''),
                    proposedValue: String(item.proposedValue ?? ''),
                    reason: String(item.reason ?? ''),
                    impactHypothesis: String(item.impactHypothesis ?? ''),
                    riskOfChange: String(item.riskOfChange ?? 'MEDIUM'),
                    status: String(item.status ?? 'UNKNOWN'),
                })),
        } : null,
    };
}

export const getLatestAiSuggestions = async (): Promise<AiSuggestionLatestResponse> => {
    const res = await apiClient.get<AiSuggestionLatestResponse>('/api/v1/ai/suggestions/latest');
    return normalizeAiSuggestionLatestResponse(res.data);
};

export const acceptAiBatch = async (batchId: string): Promise<void> => {
    await apiClient.post(`/api/v1/ai/suggestions/${batchId}/accept`);
};

export const rejectAiBatch = async (batchId: string): Promise<void> => {
    await apiClient.post(`/api/v1/ai/suggestions/${batchId}/reject`);
};

// --- Settings API ---

export const getSettings = async (): Promise<SettingsDTO> => {
    const res = await apiClient.get<SettingsDTO>('/api/v1/settings');
    return res.data;
};

export const updateSettings = async (payload: SettingsUpdatePayload): Promise<SettingsDTO> => {
    const res = await apiClient.post<SettingsDTO>('/api/v1/settings', payload);
    return res.data;
};

export const previewRisk = async (payload: RiskPreviewRequestDTO): Promise<RiskPreviewResponseDTO> => {
    const res = await apiClient.post<RiskPreviewResponseDTO>('/api/v1/settings/risk-preview', payload);
    return res.data;
};

export const getLiveAiModels = async (): Promise<AiModelsResponseDTO> => {
    const res = await apiClient.get<AiModelsResponseDTO>('/api/v1/ai/models');
    return res.data;
};

export const updateLiveAiModels = async (payload: AiModelsUpdateRequestDTO): Promise<AiModelsResponseDTO> => {
    const res = await apiClient.post<AiModelsResponseDTO>('/api/v1/ai/models', payload);
    return res.data;
};

export const testLiveAiModels = async (taskType: AiTaskType = 'SUGGESTION_BATCH'): Promise<AiModelsTestResponseDTO> => {
    const res = await apiClient.post<AiModelsTestResponseDTO>('/api/v1/ai/models/test', { taskType });
    return res.data;
};
