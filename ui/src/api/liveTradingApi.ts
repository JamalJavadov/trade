import { apiClient } from './axiosSetup';
import type { RecommendationPlaceabilityDTO } from './client';

export interface LiveTradeBlockedReasonDTO {
    code: string;
    message: string;
    source: string | null;
    details: Record<string, unknown>;
}

export interface LiveTradingProbeResultDTO {
    name: string;
    method: string;
    endpoint: string;
    baseUrl: string | null;
    success: boolean;
    status: number | null;
    binanceCode: number | null;
    binanceMessage: string | null;
    blockerCode: string | null;
    message: string | null;
}

export interface LiveTradingBinanceStatusDTO {
    credentialsPresent: boolean;
    authValid: boolean | null;
    futuresOrderReadOk: boolean | null;
    positionModeReadOk: boolean | null;
    ipAllowlistOk: boolean | null;
    futuresPermissionOk: boolean | null;
    timestampOk: boolean | null;
    signingOk: boolean | null;
    endpointFamily: string | null;
    baseUrl: string | null;
    spotBaseUrl: string | null;
    recvWindowMs: number | null;
    localTimestampMs: number | null;
    serverTimestampMs: number | null;
    timestampSkewMs: number | null;
    requestIpHint: string | null;
    blockerCode: string | null;
    blockerMessage: string | null;
    endpointResults: LiveTradingProbeResultDTO[];
}

export interface LiveTradingRuntimeStatusDTO {
    liveExecutionEnabled: boolean;
    readOnly: boolean;
    tradingEnabled: boolean;
    runtimeReady: boolean;
    recommendationStale: boolean;
    staleThresholdSeconds: number;
    recommendationAgeSeconds: number | null;
    duplicateSubmitBlocked: boolean;
}

export interface LiveTradingLocalRequestDTO {
    allowed: boolean;
    remoteAddress: string | null;
    forwardedFor: string | null;
    origin: string | null;
    failureReason: string | null;
}

export interface LiveTradeExecutionEventDTO {
    id: string | null;
    eventType: string;
    eventStatus: string;
    message: string;
    errorCode: string | null;
    payload: Record<string, unknown>;
    createdAt: string | null;
}

export interface LiveTradeExecutionDTO {
    id: string;
    recommendationId: string;
    symbol: string;
    side: string;
    triggerMode: string;
    operatorId: string | null;
    traceId: string | null;
    dryRun: boolean;
    executionState: string;
    errorCode: string | null;
    errorMessage: string | null;
    createdAt: string | null;
    updatedAt: string | null;
    submittedAt: string | null;
    completedAt: string | null;
    lastReconciledAt: string | null;
    reconcileCount: number;
    orderRefs: {
        entryClientOrderId: string | null;
        slClientOrderId: string | null;
        tpClientOrderId: string | null;
        emergencyCloseClientOrderId: string | null;
        entryOrderId: number | null;
        slOrderId: number | null;
        tpOrderId: number | null;
        emergencyCloseOrderId: number | null;
    };
    payloadSnapshot: Record<string, unknown>;
    preflight: Record<string, unknown>;
    exchangeResponse: Record<string, unknown>;
    events: LiveTradeExecutionEventDTO[];
}

export interface LiveTradingPreflightDTO {
    recommendationId: string | null;
    symbol: string | null;
    side: string | null;
    allowed: boolean;
    executable: boolean;
    executionEnabled: boolean;
    checkedAt: string | null;
    runtime: LiveTradingRuntimeStatusDTO;
    localRequest: LiveTradingLocalRequestDTO;
    binance: LiveTradingBinanceStatusDTO;
    exchangeValidation: {
        valid: boolean;
        markPrice: number | string | null;
        quantity: number | string | null;
        entryNotionalUsdt: number | string | null;
        tickSize: number | string | null;
        stepSize: number | string | null;
        minQty: number | string | null;
        minNotional: number | string | null;
        slStopPrice: number | string | null;
        tpStopPrice: number | string | null;
        leverage: number | null;
        marginMode: string | null;
        positionMode: string | null;
        failures: string[];
    };
    placeability: RecommendationPlaceabilityDTO | null;
    placeabilityOk: boolean | null;
    blockedReasons: LiveTradeBlockedReasonDTO[];
}

export interface LiveTradeExecutionRequestDTO {
    clientRequestId: string;
    operatorNote?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

function toStringValue(value: unknown, fallback: string | null = null): string | null {
    return typeof value === 'string' ? value : fallback;
}

function toBoolean(value: unknown, fallback = false): boolean {
    return typeof value === 'boolean' ? value : fallback;
}

function toBooleanOrNull(value: unknown): boolean | null {
    return typeof value === 'boolean' ? value : null;
}

function toNumber(value: unknown, fallback = 0): number {
    if (typeof value === 'number' && Number.isFinite(value)) {
        return value;
    }
    if (typeof value === 'string' && value.trim() !== '') {
        const parsed = Number(value);
        if (Number.isFinite(parsed)) {
            return parsed;
        }
    }
    return fallback;
}

function toNumberOrNull(value: unknown): number | null {
    if (typeof value === 'number' && Number.isFinite(value)) {
        return value;
    }
    if (typeof value === 'string' && value.trim() !== '') {
        const parsed = Number(value);
        if (Number.isFinite(parsed)) {
            return parsed;
        }
    }
    return null;
}

function toObject(value: unknown): Record<string, unknown> {
    return isRecord(value) ? value : {};
}

function toScalar(value: unknown): string | number | null {
    if (typeof value === 'string' || typeof value === 'number') {
        return value;
    }
    return null;
}

function toStringArray(value: unknown): string[] {
    if (!Array.isArray(value)) {
        return [];
    }
    return value.filter((item): item is string => typeof item === 'string');
}

function normalizeBlockedReason(value: unknown): LiveTradeBlockedReasonDTO {
    const raw = isRecord(value) ? value : {};
    return {
        code: typeof raw.code === 'string' ? raw.code : 'UNKNOWN',
        message: typeof raw.message === 'string' ? raw.message : 'Unknown blocked reason.',
        source: toStringValue(raw.source),
        details: toObject(raw.details),
    };
}

function normalizeProbeResult(value: unknown): LiveTradingProbeResultDTO {
    const raw = isRecord(value) ? value : {};
    return {
        name: typeof raw.name === 'string' ? raw.name : 'unknownProbe',
        method: typeof raw.method === 'string' ? raw.method : 'GET',
        endpoint: typeof raw.endpoint === 'string' ? raw.endpoint : '',
        baseUrl: toStringValue(raw.baseUrl),
        success: toBoolean(raw.success),
        status: toNumberOrNull(raw.status),
        binanceCode: toNumberOrNull(raw.binanceCode),
        binanceMessage: toStringValue(raw.binanceMessage),
        blockerCode: toStringValue(raw.blockerCode),
        message: toStringValue(raw.message),
    };
}

function normalizeExecutionEvent(value: unknown): LiveTradeExecutionEventDTO {
    const raw = isRecord(value) ? value : {};
    return {
        id: typeof raw.id === 'string' ? raw.id : null,
        eventType: typeof raw.eventType === 'string' ? raw.eventType : 'UNKNOWN',
        eventStatus: typeof raw.eventStatus === 'string' ? raw.eventStatus : 'UNKNOWN',
        message: typeof raw.message === 'string' ? raw.message : 'No event message.',
        errorCode: toStringValue(raw.errorCode),
        payload: toObject(raw.payload),
        createdAt: toStringValue(raw.createdAt),
    };
}

function normalizeExecution(value: unknown): LiveTradeExecutionDTO {
    const raw = isRecord(value) ? value : {};
    const orderRefs = isRecord(raw.orderRefs) ? raw.orderRefs : {};
    return {
        id: typeof raw.id === 'string' ? raw.id : '',
        recommendationId: typeof raw.recommendationId === 'string' ? raw.recommendationId : '',
        symbol: typeof raw.symbol === 'string' ? raw.symbol : '',
        side: typeof raw.side === 'string' ? raw.side : '',
        triggerMode: typeof raw.triggerMode === 'string' ? raw.triggerMode : 'UNKNOWN',
        operatorId: toStringValue(raw.operatorId),
        traceId: toStringValue(raw.traceId),
        dryRun: toBoolean(raw.dryRun),
        executionState: typeof raw.executionState === 'string' ? raw.executionState : 'UNKNOWN',
        errorCode: toStringValue(raw.errorCode),
        errorMessage: toStringValue(raw.errorMessage),
        createdAt: toStringValue(raw.createdAt),
        updatedAt: toStringValue(raw.updatedAt),
        submittedAt: toStringValue(raw.submittedAt),
        completedAt: toStringValue(raw.completedAt),
        lastReconciledAt: toStringValue(raw.lastReconciledAt),
        reconcileCount: toNumber(raw.reconcileCount, 0),
        orderRefs: {
            entryClientOrderId: toStringValue(orderRefs.entryClientOrderId),
            slClientOrderId: toStringValue(orderRefs.slClientOrderId),
            tpClientOrderId: toStringValue(orderRefs.tpClientOrderId),
            emergencyCloseClientOrderId: toStringValue(orderRefs.emergencyCloseClientOrderId),
            entryOrderId: typeof orderRefs.entryOrderId === 'number' ? orderRefs.entryOrderId : null,
            slOrderId: typeof orderRefs.slOrderId === 'number' ? orderRefs.slOrderId : null,
            tpOrderId: typeof orderRefs.tpOrderId === 'number' ? orderRefs.tpOrderId : null,
            emergencyCloseOrderId: typeof orderRefs.emergencyCloseOrderId === 'number'
                ? orderRefs.emergencyCloseOrderId
                : null,
        },
        payloadSnapshot: toObject(raw.payloadSnapshot),
        preflight: toObject(raw.preflight),
        exchangeResponse: toObject(raw.exchangeResponse),
        events: Array.isArray(raw.events) ? raw.events.map(normalizeExecutionEvent) : [],
    };
}

function normalizePreflight(value: unknown): LiveTradingPreflightDTO {
    const raw = isRecord(value) ? value : {};
    const exchangeValidation = isRecord(raw.exchangeValidation) ? raw.exchangeValidation : {};
    const runtime = isRecord(raw.runtime) ? raw.runtime : {};
    const localRequest = isRecord(raw.localRequest) ? raw.localRequest : {};
    const binance = isRecord(raw.binance) ? raw.binance : {};
    return {
        recommendationId: typeof raw.recommendationId === 'string' ? raw.recommendationId : null,
        symbol: toStringValue(raw.symbol),
        side: toStringValue(raw.side),
        allowed: toBoolean(raw.allowed),
        executable: toBoolean(raw.executable, toBoolean(raw.allowed)),
        executionEnabled: toBoolean(raw.executionEnabled),
        checkedAt: toStringValue(raw.checkedAt),
        runtime: {
            liveExecutionEnabled: toBoolean(runtime.liveExecutionEnabled),
            readOnly: toBoolean(runtime.readOnly),
            tradingEnabled: toBoolean(runtime.tradingEnabled, true),
            runtimeReady: toBoolean(runtime.runtimeReady, true),
            recommendationStale: toBoolean(runtime.recommendationStale),
            staleThresholdSeconds: toNumber(runtime.staleThresholdSeconds, 0),
            recommendationAgeSeconds: toNumberOrNull(runtime.recommendationAgeSeconds),
            duplicateSubmitBlocked: toBoolean(runtime.duplicateSubmitBlocked),
        },
        localRequest: {
            allowed: toBoolean(localRequest.allowed, true),
            remoteAddress: toStringValue(localRequest.remoteAddress),
            forwardedFor: toStringValue(localRequest.forwardedFor),
            origin: toStringValue(localRequest.origin),
            failureReason: toStringValue(localRequest.failureReason),
        },
        binance: {
            credentialsPresent: toBoolean(binance.credentialsPresent),
            authValid: toBooleanOrNull(binance.authValid),
            futuresOrderReadOk: toBooleanOrNull(binance.futuresOrderReadOk),
            positionModeReadOk: toBooleanOrNull(binance.positionModeReadOk),
            ipAllowlistOk: toBooleanOrNull(binance.ipAllowlistOk),
            futuresPermissionOk: toBooleanOrNull(binance.futuresPermissionOk),
            timestampOk: toBooleanOrNull(binance.timestampOk),
            signingOk: toBooleanOrNull(binance.signingOk),
            endpointFamily: toStringValue(binance.endpointFamily),
            baseUrl: toStringValue(binance.baseUrl),
            spotBaseUrl: toStringValue(binance.spotBaseUrl),
            recvWindowMs: toNumberOrNull(binance.recvWindowMs),
            localTimestampMs: toNumberOrNull(binance.localTimestampMs),
            serverTimestampMs: toNumberOrNull(binance.serverTimestampMs),
            timestampSkewMs: toNumberOrNull(binance.timestampSkewMs),
            requestIpHint: toStringValue(binance.requestIpHint),
            blockerCode: toStringValue(binance.blockerCode),
            blockerMessage: toStringValue(binance.blockerMessage),
            endpointResults: Array.isArray(binance.endpointResults)
                ? binance.endpointResults.map(normalizeProbeResult)
                : [],
        },
        exchangeValidation: {
            valid: toBoolean(exchangeValidation.valid, true),
            markPrice: toScalar(exchangeValidation.markPrice),
            quantity: toScalar(exchangeValidation.quantity),
            entryNotionalUsdt: toScalar(exchangeValidation.entryNotionalUsdt),
            tickSize: toScalar(exchangeValidation.tickSize),
            stepSize: toScalar(exchangeValidation.stepSize),
            minQty: toScalar(exchangeValidation.minQty),
            minNotional: toScalar(exchangeValidation.minNotional),
            slStopPrice: toScalar(exchangeValidation.slStopPrice),
            tpStopPrice: toScalar(exchangeValidation.tpStopPrice),
            leverage: typeof exchangeValidation.leverage === 'number' ? exchangeValidation.leverage : null,
            marginMode: toStringValue(exchangeValidation.marginMode),
            positionMode: toStringValue(exchangeValidation.positionMode),
            failures: toStringArray(exchangeValidation.failures),
        },
        placeability: isRecord(raw.placeability) ? raw.placeability as unknown as RecommendationPlaceabilityDTO : null,
        placeabilityOk: toBooleanOrNull(raw.placeabilityOk),
        blockedReasons: Array.isArray(raw.blockedReasons)
            ? raw.blockedReasons.map(normalizeBlockedReason)
            : [],
    };
}

export async function getRecommendationExecutionPreflight(recommendationId: string): Promise<LiveTradingPreflightDTO> {
    const response = await apiClient.get<LiveTradingPreflightDTO>(
        `/api/v1/recommendations/${recommendationId}/execution-preflight`,
    );
    return normalizePreflight(response.data);
}

export async function getLiveTradingHealth(symbol?: string | null): Promise<LiveTradingPreflightDTO> {
    const response = await apiClient.get<LiveTradingPreflightDTO>('/api/v1/live-trading/health', {
        params: {
            symbol: symbol ?? undefined,
        },
    });
    return normalizePreflight(response.data);
}

export async function executeRecommendationLive(
    recommendationId: string,
    payload: LiveTradeExecutionRequestDTO,
): Promise<LiveTradeExecutionDTO> {
    const response = await apiClient.post<LiveTradeExecutionDTO>(
        `/api/v1/recommendations/${recommendationId}/execute-live`,
        payload,
    );
    return normalizeExecution(response.data);
}

export async function getLiveExecution(executionId: string): Promise<LiveTradeExecutionDTO> {
    const response = await apiClient.get<LiveTradeExecutionDTO>(`/api/v1/live-trading/executions/${executionId}`);
    return normalizeExecution(response.data);
}

export async function listLiveExecutions(options?: {
    recommendationId?: string | null;
    limit?: number;
}): Promise<LiveTradeExecutionDTO[]> {
    const response = await apiClient.get<LiveTradeExecutionDTO[]>('/api/v1/live-trading/executions', {
        params: {
            recommendationId: options?.recommendationId ?? undefined,
            limit: options?.limit ?? undefined,
        },
    });
    if (!Array.isArray(response.data)) {
        return [];
    }
    return response.data.map(normalizeExecution);
}
