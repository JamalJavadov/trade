import { apiClient } from './axiosSetup';
import type { BudgetTargetSyncHealthDTO, LiveTradeExecutionDTO } from './liveTradingApi';
import { normalizeExecution, normalizeSyncHealth } from './liveTradingApi';

export interface BudgetTargetSessionDTO {
    id: string;
    status: string;
    stopReason: string | null;
    stopReasonMessage: string | null;
    budgetAmountUsdt: number | null;
    targetProfitUsdt: number | null;
    completionReason: string | null;
    sessionBudgetUsdt: number | null;
    finalTargetNetProfitUsdt: number | null;
    realizedNetPnlUsdt: number | null;
    unrealizedNetPnlUsdt: number | null;
    remainingBankrollUsdt: number | null;
    maxConcurrentPositions: number;
    activePositionsCount: number;
    openedPositionsTotal: number;
    closedPositionsTotal: number;
    activeTradeLimit: number;
    activeTradeCount: number;
    openedTradeCount: number;
    pendingScanRunId: string | null;
    stopRequested: boolean;
    lastErrorCode: string | null;
    lastErrorMessage: string | null;
    failureReasonCode: string | null;
    failureReasonMessage: string | null;
    executionFailureCount: number;
    startedAt: string | null;
    endedAt: string | null;
    completedAt: string | null;
    updatedAt: string | null;
    syncHealth: BudgetTargetSyncHealthDTO | null;
}

export interface BudgetTargetSessionEventDTO {
    id: string;
    executionId: string | null;
    eventType: string;
    eventStatus: string;
    before: Record<string, unknown>;
    after: Record<string, unknown>;
    notes: string | null;
    traceId: string | null;
    eventTs: string | null;
    message: string | null;
    reasonCode: string | null;
    payload: Record<string, unknown>;
    createdAt: string | null;
}

export interface BudgetTargetAutoExecutionStateDTO {
    config: {
        enabled: boolean;
        armed: boolean;
        readOnly: boolean;
        maxConcurrentPositions: number;
        defaultBudgetUsdt: number;
        defaultTargetProfitUsdt: number;
        allowNewSessionStart: boolean;
        allowCloseAllOnTarget: boolean;
        killSwitch: boolean;
        requireBinanceHealthPass: boolean;
        requireOperatorConfirmationForStop: boolean;
        sessionTimeoutMinutes: number;
    };
    activeSession: BudgetTargetSessionDTO | null;
    latestSession: BudgetTargetSessionDTO | null;
    primaryBlockedReasonCode: string | null;
    primaryBlockedReasonMessage: string | null;
    primaryBlockedReasonSource: string | null;
    syncHealth: BudgetTargetSyncHealthDTO | null;
    orders: LiveTradeExecutionDTO[];
    events: BudgetTargetSessionEventDTO[];
    serverTime: string | null;
}

export interface BudgetTargetCriticalErrorDTO {
    sourceType: string | null;
    eventCategory: string | null;
    eventType: string | null;
    code: string | null;
    message: string | null;
    eventTs: string | null;
    executionId: string | null;
}

export interface BudgetTargetSessionConfigSnapshotDTO {
    controlCenterVersion: number | null;
    controlCenterUpdatedAt: string | null;
    startedBy: string | null;
    traceId: string | null;
    sessionBudgetUsdt: number | null;
    targetProfitUsdt: number | null;
    maxConcurrentPositions: number | null;
    autoTargetMode: {
        enabled: boolean;
        armed: boolean;
        readOnly: boolean;
        defaultBudgetUsdt: number | null;
        defaultTargetProfitUsdt: number | null;
        maxConcurrentPositions: number | null;
        allowNewSessionStart: boolean;
        allowCloseAllOnTarget: boolean;
        killSwitch: boolean;
        requireBinanceHealthPass: boolean;
        requireOperatorConfirmationForStop: boolean;
        sessionTimeoutMinutes: number | null;
    };
    liveExecution: {
        readOnly: boolean;
        enabled: boolean;
    };
    scan: {
        safeMode: boolean;
    };
}

export interface BudgetTargetSessionSummaryDTO {
    id: string;
    status: string;
    startedAt: string | null;
    endedAt: string | null;
    startedBy: string | null;
    startReason: string | null;
    targetSatisfiedAt: string | null;
    budgetAmountUsdt: number | null;
    targetProfitUsdt: number | null;
    realizedNetPnlUsdt: number | null;
    totalGrossPnlUsdt: number | null;
    feeTotalUsdt: number | null;
    winCount: number;
    lossCount: number;
    activeTradeCount: number;
    completedTradeCount: number;
    stopReason: string | null;
    mostRecentCriticalError: BudgetTargetCriticalErrorDTO | null;
    syncHealth: BudgetTargetSyncHealthDTO | null;
}

export interface BudgetTargetSessionDetailDTO {
    summary: BudgetTargetSessionSummaryDTO | null;
    configSnapshot: BudgetTargetSessionConfigSnapshotDTO;
    pendingScanRunId: string | null;
    traceId: string | null;
    latestCriticalError: BudgetTargetCriticalErrorDTO | null;
    syncHealth: BudgetTargetSyncHealthDTO | null;
    lastEventAt: string | null;
    timelineEventCount: number;
    tradeCount: number;
}

export interface BudgetTargetTradeHistoryItemDTO {
    executionId: string;
    recommendationId: string | null;
    scanRunId: string | null;
    symbol: string;
    side: string;
    triggerMode: string | null;
    allocatedBudgetSliceUsdt: number | null;
    reservedMarginUsdt: number | null;
    positionSlot: number | null;
    openedAt: string | null;
    closedAt: string | null;
    executionState: string;
    openReason: string | null;
    closeReason: string | null;
    realizedGrossPnlUsdt: number | null;
    realizedFeesUsdt: number | null;
    realizedNetPnlUsdt: number | null;
    outcome: string;
    latestCriticalError: BudgetTargetCriticalErrorDTO | null;
}

export interface BudgetTargetTradeHistoryResponseDTO {
    items: BudgetTargetTradeHistoryItemDTO[];
    total: number;
    activeCount: number;
    completedCount: number;
}

export interface BudgetTargetEventTimelineItemDTO {
    id: string;
    sourceType: string;
    eventCategory: string;
    severity: string;
    eventTs: string | null;
    sessionId: string | null;
    executionId: string | null;
    recommendationId: string | null;
    scanRunId: string | null;
    symbol: string | null;
    eventType: string;
    status: string | null;
    reasonCode: string | null;
    actor: string | null;
    message: string | null;
    summaryPayload: Record<string, unknown>;
    debugAvailable: boolean;
}

export interface BudgetTargetEventTimelineResponseDTO {
    items: BudgetTargetEventTimelineItemDTO[];
    total: number;
    filteredCount: number;
}

export interface BudgetTargetTradeOrderDTO {
    id: string;
    orderRole: string;
    clientOrderId: string | null;
    exchangeOrderId: number | null;
    clientAlgoId: string | null;
    exchangeAlgoId: number | null;
    requestedQty: number | null;
    executedQty: number | null;
    limitPrice: number | null;
    triggerPrice: number | null;
    avgFillPrice: number | null;
    orderStatus: string | null;
    requestPayload: Record<string, unknown>;
    responsePayload: Record<string, unknown>;
    snapshotPayload: Record<string, unknown>;
    createdAt: string | null;
    updatedAt: string | null;
}

export interface BudgetTargetTradeClosureDTO {
    id: string;
    closeReason: string | null;
    closedQty: number | null;
    closedPrice: number | null;
    closingClientOrderId: string | null;
    closingOrderId: number | null;
    finalPositionSnapshot: Record<string, unknown>;
    closeResponse: Record<string, unknown>;
    closedAt: string | null;
}

export interface BudgetTargetPnlLedgerEntryDTO {
    id: string;
    eventType: string;
    amountUsdt: number | null;
    eventTs: string | null;
    sourceType: string | null;
    sourceRef: string | null;
    notes: string | null;
    before: Record<string, unknown>;
    after: Record<string, unknown>;
}

export interface ExchangeSyncSnapshotDTO {
    id: string;
    sessionId: string | null;
    executionId: string | null;
    symbol: string;
    syncType: string | null;
    syncStatus: string | null;
    traceId: string | null;
    errorCode: string | null;
    errorMessage: string | null;
    divergenceDetected: boolean;
    requiresIntervention: boolean;
    openPosition: boolean;
    activeOpenOrderCount: number;
    activeProtectionOrderCount: number;
    stopLossActive: boolean;
    takeProfitActive: boolean;
    emergencyCloseWorking: boolean;
    emergencyCloseFilled: boolean;
    protectionTriggered: boolean;
    entryOrderStatus: string | null;
    stopLossStatus: string | null;
    takeProfitStatus: string | null;
    emergencyCloseStatus: string | null;
    positionQuantity: number | null;
    actualFilledQty: number | null;
    avgFillPrice: number | null;
    entryPrice: number | null;
    markPrice: number | null;
    realizedGrossPnlUsdt: number | null;
    realizedFeesUsdt: number | null;
    realizedNetPnlUsdt: number | null;
    unrealizedPnlUsdt: number | null;
    lastSuccessfulSyncAt: string | null;
    syncCompletedAt: string | null;
    snapshot: Record<string, unknown>;
}

export interface BudgetTargetTradeDetailDTO {
    trade: BudgetTargetTradeHistoryItemDTO | null;
    execution: LiveTradeExecutionDTO | null;
    decisionAudits: BudgetTargetEventTimelineItemDTO[];
    orders: BudgetTargetTradeOrderDTO[];
    closure: BudgetTargetTradeClosureDTO | null;
    pnlLedgerEntries: BudgetTargetPnlLedgerEntryDTO[];
    syncSnapshots: ExchangeSyncSnapshotDTO[];
    generatedAt: string | null;
}

export interface BudgetTargetSessionAuditReplayDTO {
    session: BudgetTargetSessionDetailDTO | null;
    timeline: BudgetTargetEventTimelineItemDTO[];
    trades: Record<string, BudgetTargetTradeDetailDTO>;
    generatedAt: string | null;
}

export interface BudgetTargetSessionStreamEventDTO {
    eventId: number;
    sessionId: string | null;
    summary: BudgetTargetSessionSummaryDTO | null;
    timelineItem: BudgetTargetEventTimelineItemDTO | null;
}

export type BudgetTargetAutoExecutionCommand = 'TURN_ON' | 'TURN_OFF';

export interface BudgetTargetAutoExecutionStateRequestDTO {
    command: BudgetTargetAutoExecutionCommand;
    confirmStop?: boolean;
    reason?: string;
    budgetAmountUsdt?: number;
    targetProfitUsdt?: number;
}

export interface StartBudgetTargetAutoExecutionSessionRequestDTO {
    budgetAmountUsdt: number;
    targetProfitUsdt: number;
    reason?: string;
}

export interface StopBudgetTargetAutoExecutionSessionRequestDTO {
    confirmStop?: boolean;
    reason?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

function toStringValue(value: unknown): string | null {
    return typeof value === 'string' ? value : null;
}

function toNumber(value: unknown, fallback: number): number {
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

function toBoolean(value: unknown, fallback = false): boolean {
    return typeof value === 'boolean' ? value : fallback;
}

function toObject(value: unknown): Record<string, unknown> {
    return isRecord(value) ? value : {};
}

function normalizeSession(value: unknown): BudgetTargetSessionDTO | null {
    if (!isRecord(value)) {
        return null;
    }
    return {
        id: typeof value.id === 'string' ? value.id : '',
        status: typeof value.status === 'string' ? value.status : 'UNKNOWN',
        stopReason: toStringValue(value.stopReason),
        stopReasonMessage: toStringValue(value.stopReasonMessage) ?? toStringValue(value.lastErrorMessage) ?? toStringValue(value.stopReason),
        budgetAmountUsdt: toNumberOrNull(value.budgetAmountUsdt),
        targetProfitUsdt: toNumberOrNull(value.targetProfitUsdt),
        completionReason: toStringValue(value.completionReason) ?? toStringValue(value.stopReason),
        sessionBudgetUsdt: toNumberOrNull(value.sessionBudgetUsdt) ?? toNumberOrNull(value.budgetAmountUsdt),
        finalTargetNetProfitUsdt: toNumberOrNull(value.finalTargetNetProfitUsdt) ?? toNumberOrNull(value.targetProfitUsdt),
        realizedNetPnlUsdt: toNumberOrNull(value.realizedNetPnlUsdt),
        unrealizedNetPnlUsdt: toNumberOrNull(value.unrealizedNetPnlUsdt),
        remainingBankrollUsdt: toNumberOrNull(value.remainingBankrollUsdt),
        maxConcurrentPositions: toNumber(value.maxConcurrentPositions, 3),
        activePositionsCount: toNumber(value.activePositionsCount, 0),
        openedPositionsTotal: toNumber(value.openedPositionsTotal, 0),
        closedPositionsTotal: toNumber(value.closedPositionsTotal, 0),
        activeTradeLimit: toNumber(value.activeTradeLimit, toNumber(value.maxConcurrentPositions, 3)),
        activeTradeCount: toNumber(value.activeTradeCount, toNumber(value.activePositionsCount, 0)),
        openedTradeCount: toNumber(value.openedTradeCount, toNumber(value.openedPositionsTotal, 0)),
        pendingScanRunId: toStringValue(value.pendingScanRunId),
        stopRequested: toBoolean(value.stopRequested),
        lastErrorCode: toStringValue(value.lastErrorCode),
        lastErrorMessage: toStringValue(value.lastErrorMessage),
        failureReasonCode: toStringValue(value.failureReasonCode) ?? toStringValue(value.lastErrorCode),
        failureReasonMessage: toStringValue(value.failureReasonMessage) ?? toStringValue(value.lastErrorMessage),
        executionFailureCount: toNumber(value.executionFailureCount, 0),
        startedAt: toStringValue(value.startedAt),
        endedAt: toStringValue(value.endedAt),
        completedAt: toStringValue(value.completedAt) ?? toStringValue(value.endedAt),
        updatedAt: toStringValue(value.updatedAt),
        syncHealth: normalizeSyncHealth(value.syncHealth),
    };
}

function normalizeEvent(value: unknown): BudgetTargetSessionEventDTO {
    const raw = isRecord(value) ? value : {};
    return {
        id: typeof raw.id === 'string' ? raw.id : '',
        executionId: toStringValue(raw.executionId),
        eventType: typeof raw.eventType === 'string' ? raw.eventType : 'UNKNOWN',
        eventStatus: typeof raw.eventStatus === 'string' ? raw.eventStatus : 'UNKNOWN',
        before: toObject(raw.before),
        after: toObject(raw.after),
        notes: toStringValue(raw.notes),
        traceId: toStringValue(raw.traceId),
        eventTs: toStringValue(raw.eventTs),
        message: toStringValue(raw.message) ?? toStringValue(raw.notes),
        reasonCode: toStringValue(raw.reasonCode),
        payload: toObject(raw.payload),
        createdAt: toStringValue(raw.createdAt),
    };
}

function normalizeState(value: unknown): BudgetTargetAutoExecutionStateDTO {
    const raw = isRecord(value) ? value : {};
    const config = isRecord(raw.config) ? raw.config : {};
    const legacyEnabled = toBoolean(config.enabled);
    return {
        config: {
            enabled: legacyEnabled,
            armed: toBoolean(config.armed, legacyEnabled),
            readOnly: toBoolean(config.readOnly),
            maxConcurrentPositions: toNumber(config.maxConcurrentPositions ?? config.maxActiveTrades, 3),
            defaultBudgetUsdt: toNumber(config.defaultBudgetUsdt ?? config.sessionBudgetUsdt, 50),
            defaultTargetProfitUsdt: toNumber(config.defaultTargetProfitUsdt ?? config.finalTargetNetProfitUsdt, 10),
            allowNewSessionStart: toBoolean(config.allowNewSessionStart, true),
            allowCloseAllOnTarget: toBoolean(config.allowCloseAllOnTarget, true),
            killSwitch: toBoolean(config.killSwitch),
            requireBinanceHealthPass: toBoolean(config.requireBinanceHealthPass, true),
            requireOperatorConfirmationForStop: toBoolean(config.requireOperatorConfirmationForStop, true),
            sessionTimeoutMinutes: toNumber(config.sessionTimeoutMinutes, 240),
        },
        activeSession: normalizeSession(raw.activeSession),
        latestSession: normalizeSession(raw.latestSession),
        primaryBlockedReasonCode: toStringValue(raw.primaryBlockedReasonCode),
        primaryBlockedReasonMessage: toStringValue(raw.primaryBlockedReasonMessage),
        primaryBlockedReasonSource: toStringValue(raw.primaryBlockedReasonSource),
        syncHealth: normalizeSyncHealth(raw.syncHealth),
        orders: Array.isArray(raw.orders) ? raw.orders.map(normalizeExecution) : [],
        events: Array.isArray(raw.events) ? raw.events.map(normalizeEvent) : [],
        serverTime: toStringValue(raw.serverTime),
    };
}

function normalizeCriticalError(value: unknown): BudgetTargetCriticalErrorDTO | null {
    if (!isRecord(value)) {
        return null;
    }
    return {
        sourceType: toStringValue(value.sourceType),
        eventCategory: toStringValue(value.eventCategory),
        eventType: toStringValue(value.eventType),
        code: toStringValue(value.code),
        message: toStringValue(value.message),
        eventTs: toStringValue(value.eventTs),
        executionId: toStringValue(value.executionId),
    };
}

function normalizeSessionSummary(value: unknown): BudgetTargetSessionSummaryDTO {
    const raw = isRecord(value) ? value : {};
    return {
        id: toStringValue(raw.id) ?? '',
        status: toStringValue(raw.status) ?? 'UNKNOWN',
        startedAt: toStringValue(raw.startedAt),
        endedAt: toStringValue(raw.endedAt),
        startedBy: toStringValue(raw.startedBy),
        startReason: toStringValue(raw.startReason),
        targetSatisfiedAt: toStringValue(raw.targetSatisfiedAt),
        budgetAmountUsdt: toNumberOrNull(raw.budgetAmountUsdt),
        targetProfitUsdt: toNumberOrNull(raw.targetProfitUsdt),
        realizedNetPnlUsdt: toNumberOrNull(raw.realizedNetPnlUsdt),
        totalGrossPnlUsdt: toNumberOrNull(raw.totalGrossPnlUsdt),
        feeTotalUsdt: toNumberOrNull(raw.feeTotalUsdt),
        winCount: toNumber(raw.winCount, 0),
        lossCount: toNumber(raw.lossCount, 0),
        activeTradeCount: toNumber(raw.activeTradeCount, 0),
        completedTradeCount: toNumber(raw.completedTradeCount, 0),
        stopReason: toStringValue(raw.stopReason),
        mostRecentCriticalError: normalizeCriticalError(raw.mostRecentCriticalError),
        syncHealth: normalizeSyncHealth(raw.syncHealth),
    };
}

function normalizeConfigSnapshot(value: unknown): BudgetTargetSessionConfigSnapshotDTO {
    const raw = isRecord(value) ? value : {};
    const autoTargetMode = isRecord(raw.autoTargetMode) ? raw.autoTargetMode : {};
    const liveExecution = isRecord(raw.liveExecution) ? raw.liveExecution : {};
    const scan = isRecord(raw.scan) ? raw.scan : {};
    return {
        controlCenterVersion: toNumberOrNull(raw.controlCenterVersion),
        controlCenterUpdatedAt: toStringValue(raw.controlCenterUpdatedAt),
        startedBy: toStringValue(raw.startedBy),
        traceId: toStringValue(raw.traceId),
        sessionBudgetUsdt: toNumberOrNull(raw.sessionBudgetUsdt),
        targetProfitUsdt: toNumberOrNull(raw.targetProfitUsdt),
        maxConcurrentPositions: toNumberOrNull(raw.maxConcurrentPositions),
        autoTargetMode: {
            enabled: toBoolean(autoTargetMode.enabled),
            armed: toBoolean(autoTargetMode.armed),
            readOnly: toBoolean(autoTargetMode.readOnly),
            defaultBudgetUsdt: toNumberOrNull(autoTargetMode.defaultBudgetUsdt),
            defaultTargetProfitUsdt: toNumberOrNull(autoTargetMode.defaultTargetProfitUsdt),
            maxConcurrentPositions: toNumberOrNull(autoTargetMode.maxConcurrentPositions),
            allowNewSessionStart: toBoolean(autoTargetMode.allowNewSessionStart),
            allowCloseAllOnTarget: toBoolean(autoTargetMode.allowCloseAllOnTarget),
            killSwitch: toBoolean(autoTargetMode.killSwitch),
            requireBinanceHealthPass: toBoolean(autoTargetMode.requireBinanceHealthPass),
            requireOperatorConfirmationForStop: toBoolean(autoTargetMode.requireOperatorConfirmationForStop),
            sessionTimeoutMinutes: toNumberOrNull(autoTargetMode.sessionTimeoutMinutes),
        },
        liveExecution: {
            readOnly: toBoolean(liveExecution.readOnly),
            enabled: toBoolean(liveExecution.enabled),
        },
        scan: {
            safeMode: toBoolean(scan.safeMode),
        },
    };
}

function normalizeTradeHistoryItem(value: unknown): BudgetTargetTradeHistoryItemDTO {
    const raw = isRecord(value) ? value : {};
    return {
        executionId: toStringValue(raw.executionId) ?? '',
        recommendationId: toStringValue(raw.recommendationId),
        scanRunId: toStringValue(raw.scanRunId),
        symbol: toStringValue(raw.symbol) ?? '',
        side: toStringValue(raw.side) ?? '',
        triggerMode: toStringValue(raw.triggerMode),
        allocatedBudgetSliceUsdt: toNumberOrNull(raw.allocatedBudgetSliceUsdt),
        reservedMarginUsdt: toNumberOrNull(raw.reservedMarginUsdt),
        positionSlot: toNumberOrNull(raw.positionSlot),
        openedAt: toStringValue(raw.openedAt),
        closedAt: toStringValue(raw.closedAt),
        executionState: toStringValue(raw.executionState) ?? 'UNKNOWN',
        openReason: toStringValue(raw.openReason),
        closeReason: toStringValue(raw.closeReason),
        realizedGrossPnlUsdt: toNumberOrNull(raw.realizedGrossPnlUsdt),
        realizedFeesUsdt: toNumberOrNull(raw.realizedFeesUsdt),
        realizedNetPnlUsdt: toNumberOrNull(raw.realizedNetPnlUsdt),
        outcome: toStringValue(raw.outcome) ?? 'UNKNOWN',
        latestCriticalError: normalizeCriticalError(raw.latestCriticalError),
    };
}

function normalizeTimelineItem(value: unknown): BudgetTargetEventTimelineItemDTO {
    const raw = isRecord(value) ? value : {};
    return {
        id: toStringValue(raw.id) ?? '',
        sourceType: toStringValue(raw.sourceType) ?? 'UNKNOWN',
        eventCategory: toStringValue(raw.eventCategory) ?? 'UNKNOWN',
        severity: toStringValue(raw.severity) ?? 'INFO',
        eventTs: toStringValue(raw.eventTs),
        sessionId: toStringValue(raw.sessionId),
        executionId: toStringValue(raw.executionId),
        recommendationId: toStringValue(raw.recommendationId),
        scanRunId: toStringValue(raw.scanRunId),
        symbol: toStringValue(raw.symbol),
        eventType: toStringValue(raw.eventType) ?? 'UNKNOWN',
        status: toStringValue(raw.status),
        reasonCode: toStringValue(raw.reasonCode),
        actor: toStringValue(raw.actor),
        message: toStringValue(raw.message),
        summaryPayload: toObject(raw.summaryPayload),
        debugAvailable: toBoolean(raw.debugAvailable),
    };
}

function normalizeSessionDetail(value: unknown): BudgetTargetSessionDetailDTO {
    const raw = isRecord(value) ? value : {};
    return {
        summary: isRecord(raw.summary) ? normalizeSessionSummary(raw.summary) : null,
        configSnapshot: normalizeConfigSnapshot(raw.configSnapshot),
        pendingScanRunId: toStringValue(raw.pendingScanRunId),
        traceId: toStringValue(raw.traceId),
        latestCriticalError: normalizeCriticalError(raw.latestCriticalError),
        syncHealth: normalizeSyncHealth(raw.syncHealth),
        lastEventAt: toStringValue(raw.lastEventAt),
        timelineEventCount: toNumber(raw.timelineEventCount, 0),
        tradeCount: toNumber(raw.tradeCount, 0),
    };
}

function normalizeTradeHistoryResponse(value: unknown): BudgetTargetTradeHistoryResponseDTO {
    const raw = isRecord(value) ? value : {};
    return {
        items: Array.isArray(raw.items) ? raw.items.map(normalizeTradeHistoryItem) : [],
        total: toNumber(raw.total, 0),
        activeCount: toNumber(raw.activeCount, 0),
        completedCount: toNumber(raw.completedCount, 0),
    };
}

function normalizeTimelineResponse(value: unknown): BudgetTargetEventTimelineResponseDTO {
    const raw = isRecord(value) ? value : {};
    return {
        items: Array.isArray(raw.items) ? raw.items.map(normalizeTimelineItem) : [],
        total: toNumber(raw.total, 0),
        filteredCount: toNumber(raw.filteredCount, 0),
    };
}

function normalizeTradeOrder(value: unknown): BudgetTargetTradeOrderDTO {
    const raw = isRecord(value) ? value : {};
    return {
        id: toStringValue(raw.id) ?? '',
        orderRole: toStringValue(raw.orderRole) ?? 'UNKNOWN',
        clientOrderId: toStringValue(raw.clientOrderId),
        exchangeOrderId: toNumberOrNull(raw.exchangeOrderId),
        clientAlgoId: toStringValue(raw.clientAlgoId),
        exchangeAlgoId: toNumberOrNull(raw.exchangeAlgoId),
        requestedQty: toNumberOrNull(raw.requestedQty),
        executedQty: toNumberOrNull(raw.executedQty),
        limitPrice: toNumberOrNull(raw.limitPrice),
        triggerPrice: toNumberOrNull(raw.triggerPrice),
        avgFillPrice: toNumberOrNull(raw.avgFillPrice),
        orderStatus: toStringValue(raw.orderStatus),
        requestPayload: toObject(raw.requestPayload),
        responsePayload: toObject(raw.responsePayload),
        snapshotPayload: toObject(raw.snapshotPayload),
        createdAt: toStringValue(raw.createdAt),
        updatedAt: toStringValue(raw.updatedAt),
    };
}

function normalizeTradeClosure(value: unknown): BudgetTargetTradeClosureDTO | null {
    if (!isRecord(value)) {
        return null;
    }
    return {
        id: toStringValue(value.id) ?? '',
        closeReason: toStringValue(value.closeReason),
        closedQty: toNumberOrNull(value.closedQty),
        closedPrice: toNumberOrNull(value.closedPrice),
        closingClientOrderId: toStringValue(value.closingClientOrderId),
        closingOrderId: toNumberOrNull(value.closingOrderId),
        finalPositionSnapshot: toObject(value.finalPositionSnapshot),
        closeResponse: toObject(value.closeResponse),
        closedAt: toStringValue(value.closedAt),
    };
}

function normalizePnlLedgerEntry(value: unknown): BudgetTargetPnlLedgerEntryDTO {
    const raw = isRecord(value) ? value : {};
    return {
        id: toStringValue(raw.id) ?? '',
        eventType: toStringValue(raw.eventType) ?? 'UNKNOWN',
        amountUsdt: toNumberOrNull(raw.amountUsdt),
        eventTs: toStringValue(raw.eventTs),
        sourceType: toStringValue(raw.sourceType),
        sourceRef: toStringValue(raw.sourceRef),
        notes: toStringValue(raw.notes),
        before: toObject(raw.before),
        after: toObject(raw.after),
    };
}

function normalizeSyncSnapshot(value: unknown): ExchangeSyncSnapshotDTO {
    const raw = isRecord(value) ? value : {};
    return {
        id: toStringValue(raw.id) ?? '',
        sessionId: toStringValue(raw.sessionId),
        executionId: toStringValue(raw.executionId),
        symbol: toStringValue(raw.symbol) ?? '',
        syncType: toStringValue(raw.syncType),
        syncStatus: toStringValue(raw.syncStatus),
        traceId: toStringValue(raw.traceId),
        errorCode: toStringValue(raw.errorCode),
        errorMessage: toStringValue(raw.errorMessage),
        divergenceDetected: toBoolean(raw.divergenceDetected),
        requiresIntervention: toBoolean(raw.requiresIntervention),
        openPosition: toBoolean(raw.openPosition),
        activeOpenOrderCount: toNumber(raw.activeOpenOrderCount, 0),
        activeProtectionOrderCount: toNumber(raw.activeProtectionOrderCount, 0),
        stopLossActive: toBoolean(raw.stopLossActive),
        takeProfitActive: toBoolean(raw.takeProfitActive),
        emergencyCloseWorking: toBoolean(raw.emergencyCloseWorking),
        emergencyCloseFilled: toBoolean(raw.emergencyCloseFilled),
        protectionTriggered: toBoolean(raw.protectionTriggered),
        entryOrderStatus: toStringValue(raw.entryOrderStatus),
        stopLossStatus: toStringValue(raw.stopLossStatus),
        takeProfitStatus: toStringValue(raw.takeProfitStatus),
        emergencyCloseStatus: toStringValue(raw.emergencyCloseStatus),
        positionQuantity: toNumberOrNull(raw.positionQuantity),
        actualFilledQty: toNumberOrNull(raw.actualFilledQty),
        avgFillPrice: toNumberOrNull(raw.avgFillPrice),
        entryPrice: toNumberOrNull(raw.entryPrice),
        markPrice: toNumberOrNull(raw.markPrice),
        realizedGrossPnlUsdt: toNumberOrNull(raw.realizedGrossPnlUsdt),
        realizedFeesUsdt: toNumberOrNull(raw.realizedFeesUsdt),
        realizedNetPnlUsdt: toNumberOrNull(raw.realizedNetPnlUsdt),
        unrealizedPnlUsdt: toNumberOrNull(raw.unrealizedPnlUsdt),
        lastSuccessfulSyncAt: toStringValue(raw.lastSuccessfulSyncAt),
        syncCompletedAt: toStringValue(raw.syncCompletedAt),
        snapshot: toObject(raw.snapshot),
    };
}

function normalizeTradeDetail(value: unknown): BudgetTargetTradeDetailDTO {
    const raw = isRecord(value) ? value : {};
    return {
        trade: isRecord(raw.trade) ? normalizeTradeHistoryItem(raw.trade) : null,
        execution: raw.execution ? normalizeExecution(raw.execution) : null,
        decisionAudits: Array.isArray(raw.decisionAudits) ? raw.decisionAudits.map(normalizeTimelineItem) : [],
        orders: Array.isArray(raw.orders) ? raw.orders.map(normalizeTradeOrder) : [],
        closure: normalizeTradeClosure(raw.closure),
        pnlLedgerEntries: Array.isArray(raw.pnlLedgerEntries) ? raw.pnlLedgerEntries.map(normalizePnlLedgerEntry) : [],
        syncSnapshots: Array.isArray(raw.syncSnapshots) ? raw.syncSnapshots.map(normalizeSyncSnapshot) : [],
        generatedAt: toStringValue(raw.generatedAt),
    };
}

function normalizeAuditReplay(value: unknown): BudgetTargetSessionAuditReplayDTO {
    const raw = isRecord(value) ? value : {};
    const trades = isRecord(raw.trades) ? raw.trades : {};
    const normalizedTrades = Object.fromEntries(
        Object.entries(trades).map(([key, item]) => [key, normalizeTradeDetail(item)]),
    );
    return {
        session: isRecord(raw.session) ? normalizeSessionDetail(raw.session) : null,
        timeline: Array.isArray(raw.timeline) ? raw.timeline.map(normalizeTimelineItem) : [],
        trades: normalizedTrades,
        generatedAt: toStringValue(raw.generatedAt),
    };
}

function normalizeStreamEvent(value: unknown): BudgetTargetSessionStreamEventDTO {
    const raw = isRecord(value) ? value : {};
    return {
        eventId: toNumber(raw.eventId, 0),
        sessionId: toStringValue(raw.sessionId),
        summary: isRecord(raw.summary) ? normalizeSessionSummary(raw.summary) : null,
        timelineItem: isRecord(raw.timelineItem) ? normalizeTimelineItem(raw.timelineItem) : null,
    };
}

export async function getBudgetTargetAutoExecutionState(): Promise<BudgetTargetAutoExecutionStateDTO> {
    const response = await apiClient.get<BudgetTargetAutoExecutionStateDTO>('/api/v1/budget-target-auto-execution/state');
    return normalizeState(response.data);
}

export async function updateBudgetTargetAutoExecutionState(
    payload: BudgetTargetAutoExecutionStateRequestDTO,
): Promise<BudgetTargetAutoExecutionStateDTO> {
    const response = await apiClient.post<BudgetTargetAutoExecutionStateDTO>(
        '/api/v1/budget-target-auto-execution/state',
        payload,
    );
    return normalizeState(response.data);
}

export async function startBudgetTargetAutoExecutionSession(
    payload: StartBudgetTargetAutoExecutionSessionRequestDTO,
): Promise<BudgetTargetAutoExecutionStateDTO> {
    return updateBudgetTargetAutoExecutionState({
        command: 'TURN_ON',
        budgetAmountUsdt: payload.budgetAmountUsdt,
        targetProfitUsdt: payload.targetProfitUsdt,
        reason: payload.reason,
    });
}

export async function stopBudgetTargetAutoExecutionSession(
    payload: StopBudgetTargetAutoExecutionSessionRequestDTO = {},
): Promise<BudgetTargetAutoExecutionStateDTO> {
    return updateBudgetTargetAutoExecutionState({
        command: 'TURN_OFF',
        confirmStop: payload.confirmStop,
        reason: payload.reason,
    });
}

export async function getBudgetTargetAutoExecutionSessions(limit = 20, offset = 0): Promise<BudgetTargetSessionSummaryDTO[]> {
    const response = await apiClient.get<BudgetTargetSessionSummaryDTO[]>('/api/v1/budget-target-auto-execution/sessions', {
        params: { limit, offset },
    });
    return Array.isArray(response.data) ? response.data.map(normalizeSessionSummary) : [];
}

export async function getBudgetTargetAutoExecutionSessionDetail(sessionId: string): Promise<BudgetTargetSessionDetailDTO> {
    const response = await apiClient.get<BudgetTargetSessionDetailDTO>(
        `/api/v1/budget-target-auto-execution/sessions/${sessionId}`,
    );
    return normalizeSessionDetail(response.data);
}

export async function getBudgetTargetAutoExecutionTimeline(
    sessionId: string,
    params: {
        limit?: number;
        offset?: number;
        category?: string | null;
        severity?: string | null;
        executionId?: string | null;
    } = {},
): Promise<BudgetTargetEventTimelineResponseDTO> {
    const response = await apiClient.get<BudgetTargetEventTimelineResponseDTO>(
        `/api/v1/budget-target-auto-execution/sessions/${sessionId}/timeline`,
        {
            params: {
                limit: params.limit ?? 100,
                offset: params.offset ?? 0,
                category: params.category ?? undefined,
                severity: params.severity ?? undefined,
                executionId: params.executionId ?? undefined,
            },
        },
    );
    return normalizeTimelineResponse(response.data);
}

export async function getBudgetTargetAutoExecutionTrades(
    sessionId: string,
    params: {
        limit?: number;
        offset?: number;
        state?: string | null;
    } = {},
): Promise<BudgetTargetTradeHistoryResponseDTO> {
    const response = await apiClient.get<BudgetTargetTradeHistoryResponseDTO>(
        `/api/v1/budget-target-auto-execution/sessions/${sessionId}/trades`,
        {
            params: {
                limit: params.limit ?? 50,
                offset: params.offset ?? 0,
                state: params.state ?? undefined,
            },
        },
    );
    return normalizeTradeHistoryResponse(response.data);
}

export async function getBudgetTargetAutoExecutionTradeDetail(
    sessionId: string,
    executionId: string,
): Promise<BudgetTargetTradeDetailDTO> {
    const response = await apiClient.get<BudgetTargetTradeDetailDTO>(
        `/api/v1/budget-target-auto-execution/sessions/${sessionId}/trades/${executionId}`,
    );
    return normalizeTradeDetail(response.data);
}

export async function getBudgetTargetAutoExecutionAuditReplay(sessionId: string): Promise<BudgetTargetSessionAuditReplayDTO> {
    const response = await apiClient.get<BudgetTargetSessionAuditReplayDTO>(
        `/api/v1/budget-target-auto-execution/sessions/${sessionId}/audit/replay`,
    );
    return normalizeAuditReplay(response.data);
}

export function parseBudgetTargetSessionStreamEvent(raw: unknown): BudgetTargetSessionStreamEventDTO {
    return normalizeStreamEvent(raw);
}
