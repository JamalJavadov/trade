import { apiClient } from './axiosSetup';
import type {
    AiModelsResponseDTO,
    AiModelsUpdateRequestDTO,
    AiModelsTestResponseDTO,
    AiTaskType,
} from './client';

const DEMO_API_PREFIX = '/api/v1/demo-trading';

export interface DemoActionResponse {
    message: string;
    running: boolean;
}

export interface DemoTradeRow {
    id: string;
    openedAt: string | null;
    closedAt: string | null;
    symbol: string;
    side: string;
    status: string;
    closeReason: string | null;
    stage: number | null;
    remainingQty: number | string | null;
    pnlUsdt: number | string | null;
    rMultiple: number | string | null;
}

export interface DemoTradeDetail {
    id: string;
    createdAt: string | null;
    openedAt: string | null;
    closedAt: string | null;
    symbol: string;
    side: string;
    leverage: number | null;
    qty: number | string | null;
    remainingQty: number | string | null;
    entryPrice: number | string | null;
    slPrice: number | string | null;
    currentSlPrice: number | string | null;
    tp1Price: number | string | null;
    tp2Price: number | string | null;
    tp3Price: number | string | null;
    workingType: string | null;
    status: string;
    closeReason: string | null;
    stage: number | null;
    riskUsdtInitial: number | string | null;
    realizedPnlUsdt: number | string | null;
    entryFeeUsdt: number | string | null;
    exitFeeUsdt: number | string | null;
    totalFeesUsdt: number | string | null;
    lastMarkPrice: number | string | null;
    pnlUsdt: number | string | null;
    rMultiple: number | string | null;
    snapshotJson: string | null;
}

export interface DemoTradeListResponse {
    limit: number;
    offset: number;
    total: number;
    trades: DemoTradeRow[];
}

export interface DemoStatusResponse {
    enabled: boolean;
    running: boolean;
    intervalMinutes: number;
    maxOpenPositions: number;
    account: {
        balanceUsdt: number | string | null;
        equityUsdt: number | string | null;
    } | null;
    openPositionsCount: number;
    closedTradesCount: number;
    lastDemoRunStatus: string;
    cycleCountTotal: number;
    cycleCountFinished: number;
    cycleCountFailed: number;
    cycleRunning: boolean;
    workflowPhase: string;
    lastDemoTradeSummary: DemoTradeRow | null;
    lastOpenTrade: DemoTradeRow | null;
    lastClosedTrade: DemoTradeRow | null;
    winRate: number | string | null;
}

export interface DemoAnalyticsMetricMap {
    total?: number;
    wins?: number;
    losses?: number;
    winRate?: number | string | null;
    avgR?: number | string | null;
    avgWinR?: number | string | null;
    avgLossR?: number | string | null;
    expectancyR?: number | string | null;
    profitFactor?: number | string | null;
    maxDrawdownPct?: number | string | null;
    avgHoldMinutes?: number | string | null;
    closeReasonDistribution?: Record<string, number>;
    [key: string]: unknown;
}

export interface DemoCohortRow {
    bucket: string;
    count: number;
    wins: number;
    losses: number;
    winRate: number | string;
    expectancyR: number | string;
}

export interface DemoFailurePattern {
    pattern: string;
    count: number;
    winRate: number | string;
    expectancyR: number | string;
    hypothesis: string;
}

export interface DemoConfigVersion {
    id: string;
    version: number;
    createdAt: string;
    active: boolean;
    configJson: string;
    changeReason: string | null;
}

export interface DemoAnalyticsSummary {
    lookback: number;
    generatedAt: string;
    metrics: DemoAnalyticsMetricMap;
    cohorts: Record<string, DemoCohortRow[]>;
    topFailurePatterns: DemoFailurePattern[];
    activeConfigVersion: DemoConfigVersion | null;
}

export interface DemoAiSuggestionBatch {
    id: string;
    createdAt: string;
    basedOnLastNTrades: number;
    status: string;
    summary: string;
    model: string | null;
    promptJson: string | null;
    responseJson: string | null;
    errorJson: string | null;
    acceptedAt: string | null;
    acceptedBy: string | null;
    rejectedAt: string | null;
    rejectReason: string | null;
    failedAt: string | null;
    errorCode: string | null;
    traceId: string | null;
    latencyMs: number | null;
    callStatus: string | null;
}

export interface DemoAiSuggestionItem {
    batchId: string;
    key: string;
    proposedValue: string;
    reason: string;
    impactHypothesis: string;
    riskOfChange: string;
    status: string;
}

export interface DemoAiSuggestionLatest {
    batch: DemoAiSuggestionBatch | null;
    items: DemoAiSuggestionItem[];
    activeConfigVersion: DemoConfigVersion | null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

function toScalar(value: unknown): string | number | null {
    return typeof value === 'string' || typeof value === 'number' ? value : null;
}

function normalizeDemoTradeRow(value: unknown): DemoTradeRow {
    const row = isRecord(value) ? value : {};
    return {
        id: String(row.id ?? ''),
        openedAt: typeof row.openedAt === 'string' ? row.openedAt : null,
        closedAt: typeof row.closedAt === 'string' ? row.closedAt : null,
        symbol: String(row.symbol ?? ''),
        side: String(row.side ?? ''),
        status: String(row.status ?? ''),
        closeReason: typeof row.closeReason === 'string' ? row.closeReason : null,
        stage: typeof row.stage === 'number' ? row.stage : null,
        remainingQty: toScalar(row.remainingQty),
        pnlUsdt: toScalar(row.pnlUsdt),
        rMultiple: toScalar(row.rMultiple ?? row.rmultiple),
    };
}

function normalizeDemoTradeDetail(value: unknown): DemoTradeDetail {
    const detail = isRecord(value) ? value : {};
    return {
        id: String(detail.id ?? ''),
        createdAt: typeof detail.createdAt === 'string' ? detail.createdAt : null,
        openedAt: typeof detail.openedAt === 'string' ? detail.openedAt : null,
        closedAt: typeof detail.closedAt === 'string' ? detail.closedAt : null,
        symbol: String(detail.symbol ?? ''),
        side: String(detail.side ?? ''),
        leverage: typeof detail.leverage === 'number' ? detail.leverage : null,
        qty: toScalar(detail.qty),
        remainingQty: toScalar(detail.remainingQty),
        entryPrice: toScalar(detail.entryPrice),
        slPrice: toScalar(detail.slPrice),
        currentSlPrice: toScalar(detail.currentSlPrice),
        tp1Price: toScalar(detail.tp1Price),
        tp2Price: toScalar(detail.tp2Price),
        tp3Price: toScalar(detail.tp3Price),
        workingType: typeof detail.workingType === 'string' ? detail.workingType : null,
        status: String(detail.status ?? ''),
        closeReason: typeof detail.closeReason === 'string' ? detail.closeReason : null,
        stage: typeof detail.stage === 'number' ? detail.stage : null,
        riskUsdtInitial: toScalar(detail.riskUsdtInitial),
        realizedPnlUsdt: toScalar(detail.realizedPnlUsdt),
        entryFeeUsdt: toScalar(detail.entryFeeUsdt),
        exitFeeUsdt: toScalar(detail.exitFeeUsdt),
        totalFeesUsdt: toScalar(detail.totalFeesUsdt),
        lastMarkPrice: toScalar(detail.lastMarkPrice),
        pnlUsdt: toScalar(detail.pnlUsdt),
        rMultiple: toScalar(detail.rMultiple ?? detail.rmultiple),
        snapshotJson: typeof detail.snapshotJson === 'string' ? detail.snapshotJson : null,
    };
}

function normalizeDemoTradeList(value: unknown): DemoTradeListResponse {
    const raw = isRecord(value) ? value : {};
    return {
        limit: typeof raw.limit === 'number' ? raw.limit : 0,
        offset: typeof raw.offset === 'number' ? raw.offset : 0,
        total: typeof raw.total === 'number' ? raw.total : 0,
        trades: Array.isArray(raw.trades) ? raw.trades.map(normalizeDemoTradeRow) : [],
    };
}

function normalizeDemoStatus(value: unknown): DemoStatusResponse {
    const raw = isRecord(value) ? value : {};
    return {
        enabled: Boolean(raw.enabled),
        running: Boolean(raw.running),
        intervalMinutes: typeof raw.intervalMinutes === 'number' ? raw.intervalMinutes : 0,
        maxOpenPositions: typeof raw.maxOpenPositions === 'number' ? raw.maxOpenPositions : 0,
        account: isRecord(raw.account)
            ? {
                balanceUsdt: toScalar(raw.account.balanceUsdt),
                equityUsdt: toScalar(raw.account.equityUsdt),
            }
            : null,
        openPositionsCount: typeof raw.openPositionsCount === 'number' ? raw.openPositionsCount : 0,
        closedTradesCount: typeof raw.closedTradesCount === 'number' ? raw.closedTradesCount : 0,
        lastDemoRunStatus: String(raw.lastDemoRunStatus ?? 'UNKNOWN'),
        cycleCountTotal: typeof raw.cycleCountTotal === 'number' ? raw.cycleCountTotal : 0,
        cycleCountFinished: typeof raw.cycleCountFinished === 'number' ? raw.cycleCountFinished : 0,
        cycleCountFailed: typeof raw.cycleCountFailed === 'number' ? raw.cycleCountFailed : 0,
        cycleRunning: Boolean(raw.cycleRunning),
        workflowPhase: String(raw.workflowPhase ?? 'UNKNOWN'),
        lastDemoTradeSummary: raw.lastDemoTradeSummary ? normalizeDemoTradeRow(raw.lastDemoTradeSummary) : null,
        lastOpenTrade: raw.lastOpenTrade ? normalizeDemoTradeRow(raw.lastOpenTrade) : null,
        lastClosedTrade: raw.lastClosedTrade ? normalizeDemoTradeRow(raw.lastClosedTrade) : null,
        winRate: toScalar(raw.winRate),
    };
}

export const demoApi = {
    async getStatus(): Promise<DemoStatusResponse> {
        const response = await apiClient.get<DemoStatusResponse>(`${DEMO_API_PREFIX}/status`);
        return normalizeDemoStatus(response.data);
    },

    async enable(): Promise<DemoActionResponse> {
        const response = await apiClient.post<DemoActionResponse>(`${DEMO_API_PREFIX}/enable`);
        return response.data;
    },

    async disable(): Promise<DemoActionResponse> {
        const response = await apiClient.post<DemoActionResponse>(`${DEMO_API_PREFIX}/disable`);
        return response.data;
    },

    async reset(): Promise<DemoActionResponse> {
        const response = await apiClient.post<DemoActionResponse>(
            `${DEMO_API_PREFIX}/reset`,
            undefined,
            { params: { confirm: true } },
        );
        return response.data;
    },

    async runOnce(): Promise<DemoActionResponse> {
        const response = await apiClient.post<DemoActionResponse>(`${DEMO_API_PREFIX}/run-once`);
        return response.data;
    },

    async getTrades(limit: number, offset: number): Promise<DemoTradeListResponse> {
        const response = await apiClient.get<DemoTradeListResponse>(`${DEMO_API_PREFIX}/trades`, {
            params: { limit, offset },
        });
        return normalizeDemoTradeList(response.data);
    },

    async getTrade(id: string): Promise<DemoTradeDetail> {
        const response = await apiClient.get<DemoTradeDetail>(`${DEMO_API_PREFIX}/trades/${id}`);
        return normalizeDemoTradeDetail(response.data);
    },

    async getOpenTrades(): Promise<DemoTradeListResponse> {
        const response = await apiClient.get<DemoTradeListResponse>(`${DEMO_API_PREFIX}/open-trades`);
        return normalizeDemoTradeList(response.data);
    },

    async getAnalytics(lookback: 10 | 50 | 100): Promise<DemoAnalyticsSummary> {
        const response = await apiClient.get<DemoAnalyticsSummary>(`${DEMO_API_PREFIX}/analytics/summary`, {
            params: { lookback },
        });
        return response.data;
    },

    async getLatestSuggestions(): Promise<DemoAiSuggestionLatest> {
        const response = await apiClient.get<DemoAiSuggestionLatest>(`${DEMO_API_PREFIX}/ai/suggestions/latest`);
        return response.data;
    },

    async acceptBatch(batchId: string): Promise<DemoActionResponse> {
        const response = await apiClient.post<DemoActionResponse>(`${DEMO_API_PREFIX}/ai/suggestions/${batchId}/accept`);
        return response.data;
    },

    async rejectBatch(batchId: string): Promise<DemoActionResponse> {
        const response = await apiClient.post<DemoActionResponse>(`${DEMO_API_PREFIX}/ai/suggestions/${batchId}/reject`);
        return response.data;
    },

    async getAiModels(): Promise<AiModelsResponseDTO> {
        const response = await apiClient.get<AiModelsResponseDTO>(`${DEMO_API_PREFIX}/ai/models`);
        return response.data;
    },

    async updateAiModels(payload: AiModelsUpdateRequestDTO): Promise<AiModelsResponseDTO> {
        const response = await apiClient.post<AiModelsResponseDTO>(`${DEMO_API_PREFIX}/ai/models`, payload);
        return response.data;
    },

    async testAiModels(taskType: AiTaskType = 'SUGGESTION_BATCH'): Promise<AiModelsTestResponseDTO> {
        const response = await apiClient.post<AiModelsTestResponseDTO>(`${DEMO_API_PREFIX}/ai/models/test`, { taskType });
        return response.data;
    },
};
