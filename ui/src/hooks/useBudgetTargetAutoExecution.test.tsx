import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useBudgetTargetAutoExecution } from './useBudgetTargetAutoExecution';

const {
    buildApiUrlMock,
    getBudgetTargetAutoExecutionStateMock,
    getBudgetTargetAutoExecutionSessionsMock,
    getBudgetTargetAutoExecutionSessionDetailMock,
    getBudgetTargetAutoExecutionTimelineMock,
    getBudgetTargetAutoExecutionTradesMock,
    getBudgetTargetAutoExecutionTradeDetailMock,
    getBudgetTargetAutoExecutionAuditReplayMock,
    parseBudgetTargetSessionStreamEventMock,
    startBudgetTargetAutoExecutionSessionMock,
    stopBudgetTargetAutoExecutionSessionMock,
    getLiveTradingHealthMock,
} = vi.hoisted(() => ({
    buildApiUrlMock: vi.fn((path: string) => path),
    getBudgetTargetAutoExecutionStateMock: vi.fn(),
    getBudgetTargetAutoExecutionSessionsMock: vi.fn(),
    getBudgetTargetAutoExecutionSessionDetailMock: vi.fn(),
    getBudgetTargetAutoExecutionTimelineMock: vi.fn(),
    getBudgetTargetAutoExecutionTradesMock: vi.fn(),
    getBudgetTargetAutoExecutionTradeDetailMock: vi.fn(),
    getBudgetTargetAutoExecutionAuditReplayMock: vi.fn(),
    parseBudgetTargetSessionStreamEventMock: vi.fn((value) => value),
    startBudgetTargetAutoExecutionSessionMock: vi.fn(),
    stopBudgetTargetAutoExecutionSessionMock: vi.fn(),
    getLiveTradingHealthMock: vi.fn(),
}));

vi.mock('../api/axiosSetup', () => ({
    buildApiUrl: buildApiUrlMock,
}));

vi.mock('../api/budgetTargetAutoExecutionApi', () => ({
    getBudgetTargetAutoExecutionState: getBudgetTargetAutoExecutionStateMock,
    getBudgetTargetAutoExecutionSessions: getBudgetTargetAutoExecutionSessionsMock,
    getBudgetTargetAutoExecutionSessionDetail: getBudgetTargetAutoExecutionSessionDetailMock,
    getBudgetTargetAutoExecutionTimeline: getBudgetTargetAutoExecutionTimelineMock,
    getBudgetTargetAutoExecutionTrades: getBudgetTargetAutoExecutionTradesMock,
    getBudgetTargetAutoExecutionTradeDetail: getBudgetTargetAutoExecutionTradeDetailMock,
    getBudgetTargetAutoExecutionAuditReplay: getBudgetTargetAutoExecutionAuditReplayMock,
    parseBudgetTargetSessionStreamEvent: parseBudgetTargetSessionStreamEventMock,
    startBudgetTargetAutoExecutionSession: startBudgetTargetAutoExecutionSessionMock,
    stopBudgetTargetAutoExecutionSession: stopBudgetTargetAutoExecutionSessionMock,
}));

vi.mock('../api/liveTradingApi', () => ({
    getLiveTradingHealth: getLiveTradingHealthMock,
}));

class MockEventSource {
    static instances: MockEventSource[] = [];

    url: string;
    onopen: ((event: Event) => void) | null = null;
    onerror: ((event: Event) => void) | null = null;
    private listeners = new Map<string, Array<(event: Event) => void>>();

    constructor(url: string) {
        this.url = url;
        MockEventSource.instances.push(this);
    }

    addEventListener(type: string, listener: (event: Event) => void) {
        const existing = this.listeners.get(type) ?? [];
        existing.push(listener);
        this.listeners.set(type, existing);
    }

    close() {
        // no-op
    }

    emitOpen() {
        this.onopen?.(new Event('open'));
    }

    emitError() {
        this.onerror?.(new Event('error'));
    }

    emit(type: string, payload: unknown) {
        const listeners = this.listeners.get(type) ?? [];
        const event = new MessageEvent(type, {
            data: JSON.stringify(payload),
        });
        for (const listener of listeners) {
            listener(event);
        }
    }
}

function buildState() {
    return {
        config: {
            enabled: true,
            armed: false,
            readOnly: false,
            maxConcurrentPositions: 3,
            defaultBudgetUsdt: 50,
            defaultTargetProfitUsdt: 10,
            allowNewSessionStart: true,
            allowCloseAllOnTarget: true,
            killSwitch: false,
            requireBinanceHealthPass: true,
            requireOperatorConfirmationForStop: true,
            sessionTimeoutMinutes: 240,
        },
        activeSession: null,
        latestSession: {
            id: 'session-1',
            status: 'RUNNING',
            budgetAmountUsdt: 50,
            targetProfitUsdt: 10,
            realizedNetPnlUsdt: 4.5,
            pendingScanRunId: null,
            stopReason: null,
            stopReasonMessage: null,
            lastErrorCode: null,
            lastErrorMessage: null,
            createdAt: '2026-03-09T00:00:00Z',
            updatedAt: '2026-03-09T00:10:00Z',
        },
        primaryBlockedReasonCode: null,
        primaryBlockedReasonMessage: null,
        primaryBlockedReasonSource: null,
        orders: [],
        events: [],
        serverTime: '2026-03-09T00:10:00Z',
    };
}

function buildHealth() {
    return {
        executable: true,
        checkedAt: '2026-03-09T00:10:00Z',
        blockedReasons: [],
        runtime: {
            runtimeReady: true,
        },
        summary: {
            connectionStatus: 'CONNECTED',
            primaryBlockerCode: null,
            primaryBlockerMessage: null,
        },
        binance: {
            blockerCode: null,
            blockerMessage: null,
        },
    };
}

function buildSummary(status = 'RUNNING') {
    return {
        id: 'session-1',
        status,
        startedAt: '2026-03-09T00:00:00Z',
        startedBy: 'operator-1',
        startReason: 'operator-start',
        budgetAmountUsdt: 50,
        targetProfitUsdt: 10,
        realizedNetPnlUsdt: 4.5,
        totalGrossPnlUsdt: 6,
        feeTotalUsdt: 1.5,
        winCount: 1,
        lossCount: 1,
        activeTradeCount: 1,
        completedTradeCount: 1,
        stopReason: null,
        mostRecentCriticalError: null,
    };
}

function buildDetail(status = 'RUNNING') {
    return {
        summary: buildSummary(status),
        configSnapshot: {
            autoTargetMode: {},
            liveExecution: {},
            scan: {},
        },
        pendingScanRunId: null,
        traceId: 'trace-session',
        latestCriticalError: null,
        lastEventAt: '2026-03-09T00:10:00Z',
        timelineEventCount: 1,
        tradeCount: 1,
    };
}

async function flushAsync() {
    await act(async () => {
        await Promise.resolve();
        await Promise.resolve();
        await Promise.resolve();
    });
}

describe('useBudgetTargetAutoExecution', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.clearAllMocks();
        MockEventSource.instances = [];
        vi.stubGlobal('EventSource', MockEventSource as unknown as typeof EventSource);

        getBudgetTargetAutoExecutionStateMock.mockResolvedValue(buildState());
        getLiveTradingHealthMock.mockResolvedValue(buildHealth());
        getBudgetTargetAutoExecutionSessionsMock.mockResolvedValue([buildSummary()]);
        getBudgetTargetAutoExecutionSessionDetailMock.mockResolvedValue(buildDetail());
        getBudgetTargetAutoExecutionTimelineMock.mockResolvedValue({
            items: [],
            total: 0,
            filteredCount: 0,
        });
        getBudgetTargetAutoExecutionTradesMock.mockResolvedValue({
            items: [],
            total: 0,
            activeCount: 0,
            completedCount: 0,
        });
        getBudgetTargetAutoExecutionTradeDetailMock.mockResolvedValue(null);
        getBudgetTargetAutoExecutionAuditReplayMock.mockResolvedValue(null);
    });

    afterEach(() => {
        vi.useRealTimers();
        vi.unstubAllGlobals();
    });

    it('reconnects the stream with lastEventId and falls back to polling after disconnect', async () => {
        const { result } = renderHook(() => useBudgetTargetAutoExecution({
            includeAudit: true,
            selectedSessionId: 'session-1',
        }));

        await flushAsync();
        await flushAsync();

        expect(result.current.sessionDetail?.summary?.id).toBe('session-1');
        expect(MockEventSource.instances).toHaveLength(1);

        await act(async () => {
            MockEventSource.instances[0].emitOpen();
        });
        expect(result.current.auditTransportMode).toBe('sse');

        await act(async () => {
            MockEventSource.instances[0].emit('session.update', {
                eventId: 9,
                sessionId: 'session-1',
                summary: buildSummary('STOPPING'),
                timelineItem: {
                    id: 'timeline-9',
                    sourceType: 'LIVE_TRADE_EXECUTION_EVENT',
                    eventCategory: 'EXCHANGE',
                    severity: 'WARN',
                    eventTs: '2026-03-09T00:11:00Z',
                    eventType: 'SAFE_CLOSE_TIMEOUT',
                    summaryPayload: {},
                    debugAvailable: true,
                },
            });
        });

        await act(async () => {
            MockEventSource.instances[0].emitError();
        });
        await flushAsync();
        expect(['reconnecting', 'polling']).toContain(result.current.auditTransportMode);

        await act(async () => {
            vi.advanceTimersByTime(3_000);
        });
        await flushAsync();
        expect(MockEventSource.instances.length).toBeGreaterThanOrEqual(2);
        expect(MockEventSource.instances.at(-1)?.url).toContain('lastEventId=9');

        await act(async () => {
            vi.advanceTimersByTime(4_000);
        });
        await flushAsync();
        expect(getBudgetTargetAutoExecutionSessionDetailMock.mock.calls.length).toBeGreaterThan(1);
        expect(result.current.streamPolling).toBe(true);
    }, 10_000);

    it('refreshes core runtime orders after a session stream update', async () => {
        let currentState = buildState();
        getBudgetTargetAutoExecutionStateMock.mockImplementation(async () => currentState);

        const { result } = renderHook(() => useBudgetTargetAutoExecution({
            includeAudit: true,
            selectedSessionId: 'session-1',
        }));

        await flushAsync();
        await flushAsync();

        expect(result.current.activeOrders).toHaveLength(0);
        expect(MockEventSource.instances).toHaveLength(1);

        await act(async () => {
            MockEventSource.instances[0].emitOpen();
        });

        currentState = {
            ...currentState,
            orders: [
                {
                    id: 'execution-1',
                    recommendationId: 'recommendation-1',
                    symbol: 'AKTUSDT',
                    side: 'SELL',
                    executionState: 'ACTIVE',
                    createdAt: '2026-03-09T00:12:00Z',
                    updatedAt: '2026-03-09T00:12:05Z',
                    submittedAt: '2026-03-09T00:12:00Z',
                },
            ],
            serverTime: '2026-03-09T00:12:05Z',
        };

        await act(async () => {
            MockEventSource.instances[0].emit('session.update', {
                eventId: 10,
                sessionId: 'session-1',
                summary: buildSummary('RUNNING'),
                timelineItem: {
                    id: 'timeline-10',
                    sourceType: 'LIVE_TRADE_EXECUTION_EVENT',
                    eventCategory: 'EXCHANGE',
                    severity: 'INFO',
                    eventTs: '2026-03-09T00:12:05Z',
                    eventType: 'ORDER_ACTIVE',
                    summaryPayload: {},
                    debugAvailable: true,
                },
            });
        });

        await act(async () => {
            vi.advanceTimersByTime(250);
        });
        await flushAsync();

        expect(getBudgetTargetAutoExecutionStateMock.mock.calls.length).toBeGreaterThan(1);
        expect(result.current.activeOrders).toHaveLength(1);
        expect(result.current.activeOrders[0].symbol).toBe('AKTUSDT');
    });

    it('uses realized pnl for target progress and applies blocker precedence in order', async () => {
        let currentState = {
            ...buildState(),
            activeSession: {
                ...buildState().latestSession,
                id: 'session-1',
                status: 'RUNNING',
                targetProfitUsdt: 10,
                realizedNetPnlUsdt: 4,
                unrealizedNetPnlUsdt: 99,
                pendingScanRunId: 'scan-123',
                stopReason: 'READ_ONLY_ENABLED',
                stopReasonMessage: 'Session-reported blocker.',
            },
            latestSession: {
                ...buildState().latestSession,
                targetProfitUsdt: 10,
                realizedNetPnlUsdt: 4,
                unrealizedNetPnlUsdt: 99,
                pendingScanRunId: 'scan-123',
                stopReason: 'READ_ONLY_ENABLED',
                stopReasonMessage: 'Session-reported blocker.',
            },
            events: [
                {
                    id: 'event-1',
                    executionId: null,
                    eventType: 'ACTIVE_LIMIT_REACHED',
                    eventStatus: 'RUNNING',
                    before: {},
                    after: {},
                    notes: 'Event blocker.',
                    traceId: 'trace-1',
                    eventTs: '2026-03-09T00:11:00Z',
                    message: 'Event blocker.',
                    reasonCode: 'ACTIVE_LIMIT_REACHED',
                    payload: {},
                    createdAt: '2026-03-09T00:11:00Z',
                },
            ],
        };
        let currentHealth = {
            ...buildHealth(),
            executable: false,
            blockedReasons: [
                {
                    code: 'BINANCE_HEALTH_FAILED',
                    message: 'Health blocker.',
                    source: 'binance',
                    details: {},
                },
            ],
            summary: {
                ...buildHealth().summary,
                primaryBlockerCode: 'BINANCE_HEALTH_FAILED',
                primaryBlockerMessage: 'Health blocker.',
            },
            binance: {
                ...buildHealth().binance,
                blockerCode: 'BINANCE_HEALTH_FAILED',
                blockerMessage: 'Health blocker.',
            },
        };

        getBudgetTargetAutoExecutionStateMock.mockImplementation(async () => currentState);
        getLiveTradingHealthMock.mockImplementation(async () => currentHealth);

        const { result } = renderHook(() => useBudgetTargetAutoExecution({
            includeAudit: false,
        }));

        await flushAsync();
        await flushAsync();

        expect(result.current.targetProgressPct).toBe(40);
        expect(result.current.remainingToTargetUsdt).toBe(6);
        expect(result.current.primaryBlockedReason).toEqual({
            code: 'PENDING_SCAN',
            message: 'Waiting for session-owned scan scan-123 to finish before opening a new trade.',
            source: 'pending-scan',
        });

        currentHealth = buildHealth();
        await act(async () => {
            await result.current.refresh(true);
        });
        await flushAsync();

        expect(result.current.primaryBlockedReason).toEqual({
            code: 'PENDING_SCAN',
            message: 'Waiting for session-owned scan scan-123 to finish before opening a new trade.',
            source: 'pending-scan',
        });

        currentState = {
            ...currentState,
            syncHealth: {
                status: 'STALE',
                gateNewTrades: true,
                gateReasonCode: 'EXCHANGE_SYNC_STALE',
                gateReasonMessage: 'Session sync is stale.',
                openPositionCount: 1,
                activeOpenOrderCount: 2,
                activeProtectionOrderCount: 2,
                closeAllInProgress: false,
                affectedExecutionIds: ['exec-1'],
            },
        };
        await act(async () => {
            await result.current.refresh(true);
        });
        await flushAsync();

        expect(result.current.primaryBlockedReason).toEqual({
            code: 'EXCHANGE_SYNC_STALE',
            message: 'Session sync is stale.',
            source: 'sync',
        });

        currentState = {
            ...currentState,
            syncHealth: null,
            activeSession: {
                ...currentState.activeSession,
                pendingScanRunId: null,
            },
            latestSession: {
                ...currentState.latestSession,
                pendingScanRunId: null,
            },
        };
        await act(async () => {
            await result.current.refresh(true);
        });
        await flushAsync();

        expect(result.current.primaryBlockedReason).toEqual({
            code: 'READ_ONLY_ENABLED',
            message: 'Session-reported blocker.',
            source: 'session',
        });

        currentState = {
            ...currentState,
            activeSession: {
                ...currentState.activeSession,
                stopReason: null,
                stopReasonMessage: null,
                lastErrorCode: null,
                lastErrorMessage: null,
            },
            latestSession: {
                ...currentState.latestSession,
                stopReason: null,
                stopReasonMessage: null,
                lastErrorCode: null,
                lastErrorMessage: null,
            },
        };
        await act(async () => {
            await result.current.refresh(true);
        });
        await flushAsync();

        expect(result.current.primaryBlockedReason).toEqual({
            code: 'ACTIVE_LIMIT_REACHED',
            message: 'Event blocker.',
            source: 'event',
        });

        currentState = {
            ...currentState,
            events: [],
        };
        await act(async () => {
            await result.current.refresh(true);
        });
        await flushAsync();

        expect(result.current.primaryBlockedReason).toEqual({
            code: null,
            message: 'No blocker reported.',
            source: 'none',
        });
    });
});
