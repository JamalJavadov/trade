import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getMock, postMock } = vi.hoisted(() => ({
    getMock: vi.fn(),
    postMock: vi.fn(),
}));

vi.mock('./axiosSetup', () => ({
    apiClient: {
        get: getMock,
        post: postMock,
    },
}));

import {
    getBudgetTargetAutoExecutionAuditReplay,
    getBudgetTargetAutoExecutionSessionDetail,
    getBudgetTargetAutoExecutionSessions,
    getBudgetTargetAutoExecutionState,
    getBudgetTargetAutoExecutionTimeline,
    getBudgetTargetAutoExecutionTradeDetail,
    getBudgetTargetAutoExecutionTrades,
    parseBudgetTargetSessionStreamEvent,
    startBudgetTargetAutoExecutionSession,
    stopBudgetTargetAutoExecutionSession,
    updateBudgetTargetAutoExecutionState,
} from './budgetTargetAutoExecutionApi';

describe('budgetTargetAutoExecutionApi', () => {
    beforeEach(() => {
        getMock.mockReset();
        postMock.mockReset();
    });

    it('normalizes stop and failure messaging fields from the backend payload', async () => {
        getMock.mockResolvedValue({
            data: {
                config: {
                    enabled: true,
                    armed: true,
                },
                activeSession: {
                    id: 'session-1',
                    status: 'STOPPING',
                    stopReason: 'READ_ONLY_ENABLED',
                    stopReasonMessage: 'Live execution entered READ-ONLY mode while the session was active.',
                    budgetAmountUsdt: '50',
                    targetProfitUsdt: '10',
                    completionReason: 'READ_ONLY_ENABLED',
                    realizedNetPnlUsdt: '6.25',
                    unrealizedNetPnlUsdt: '4.00',
                    failureReasonCode: 'UPSTREAM_TIMEOUT',
                    failureReasonMessage: 'Close-all submission timed out.',
                    executionFailureCount: '2',
                    startedAt: '2026-03-09T00:00:00Z',
                },
                latestSession: null,
                primaryBlockedReasonCode: null,
                primaryBlockedReasonMessage: null,
                primaryBlockedReasonSource: null,
                orders: [],
                events: [],
                serverTime: '2026-03-09T00:00:00Z',
            },
        });

        const result = await getBudgetTargetAutoExecutionState();

        expect(getMock).toHaveBeenCalledWith('/api/v1/budget-target-auto-execution/state');
        expect(result.activeSession?.stopReasonMessage)
            .toBe('Live execution entered READ-ONLY mode while the session was active.');
        expect(result.activeSession?.failureReasonCode).toBe('UPSTREAM_TIMEOUT');
        expect(result.activeSession?.failureReasonMessage).toBe('Close-all submission timed out.');
        expect(result.activeSession?.executionFailureCount).toBe(2);
    });

    it('derives live position fields for active orders from exchangeResponse.position', async () => {
        getMock.mockResolvedValue({
            data: {
                config: {
                    enabled: true,
                    armed: true,
                },
                activeSession: null,
                latestSession: null,
                primaryBlockedReasonCode: null,
                primaryBlockedReasonMessage: null,
                primaryBlockedReasonSource: null,
                orders: [
                    {
                        id: 'execution-1',
                        recommendationId: 'rec-1',
                        symbol: 'BTCUSDT',
                        side: 'BUY',
                        triggerMode: 'AUTO_SESSION',
                        executionState: 'ACTIVE',
                        createdAt: '2026-03-09T00:00:00Z',
                        updatedAt: '2026-03-09T00:01:00Z',
                        submittedAt: '2026-03-09T00:00:10Z',
                        exchangeResponse: {
                            position: {
                                entryPrice: '100000.0',
                                markPrice: '100250.5',
                                unRealizedProfit: '2.75',
                            },
                        },
                    },
                ],
                events: [],
                serverTime: '2026-03-09T00:01:00Z',
            },
        });

        const result = await getBudgetTargetAutoExecutionState();

        expect(result.orders[0].entryPrice).toBe(100000);
        expect(result.orders[0].markPrice).toBe(100250.5);
        expect(result.orders[0].unrealizedNetPnlUsdt).toBe(2.75);
    });

    it('posts state updates to the controller endpoint', async () => {
        postMock.mockResolvedValue({
            data: {
                config: {
                    enabled: true,
                    armed: false,
                },
                activeSession: null,
                latestSession: null,
                primaryBlockedReasonCode: null,
                primaryBlockedReasonMessage: null,
                primaryBlockedReasonSource: null,
                orders: [],
                events: [],
                serverTime: null,
            },
        });

        await updateBudgetTargetAutoExecutionState({
            command: 'TURN_OFF',
            confirmStop: true,
        });

        expect(postMock).toHaveBeenCalledWith('/api/v1/budget-target-auto-execution/state', {
            command: 'TURN_OFF',
            confirmStop: true,
        });
    });

    it('sends session budget and target through the explicit start helper', async () => {
        postMock.mockResolvedValue({
            data: {
                config: {
                    enabled: true,
                    armed: true,
                },
                activeSession: null,
                latestSession: null,
                primaryBlockedReasonCode: null,
                primaryBlockedReasonMessage: null,
                primaryBlockedReasonSource: null,
                orders: [],
                events: [],
                serverTime: null,
            },
        });

        await startBudgetTargetAutoExecutionSession({
            budgetAmountUsdt: 75,
            targetProfitUsdt: 12,
        });

        expect(postMock).toHaveBeenCalledWith('/api/v1/budget-target-auto-execution/state', {
            command: 'TURN_ON',
            budgetAmountUsdt: 75,
            targetProfitUsdt: 12,
            reason: undefined,
        });
    });

    it('keeps graceful stop payload limited to stop fields', async () => {
        postMock.mockResolvedValue({
            data: {
                config: {
                    enabled: true,
                    armed: false,
                },
                activeSession: null,
                latestSession: null,
                primaryBlockedReasonCode: null,
                primaryBlockedReasonMessage: null,
                primaryBlockedReasonSource: null,
                orders: [],
                events: [],
                serverTime: null,
            },
        });

        await stopBudgetTargetAutoExecutionSession({
            confirmStop: true,
            reason: 'operator-stop',
        });

        expect(postMock).toHaveBeenCalledWith('/api/v1/budget-target-auto-execution/state', {
            command: 'TURN_OFF',
            confirmStop: true,
            reason: 'operator-stop',
        });
    });

    it('normalizes session summary detail timeline trade and replay audit payloads', async () => {
        getMock
            .mockResolvedValueOnce({
                data: [
                    {
                        id: 'session-1',
                        status: 'STOPPED',
                        startedAt: '2026-03-09T00:00:00Z',
                        startReason: 'operator-start',
                        budgetAmountUsdt: '50',
                        targetProfitUsdt: '10',
                        realizedNetPnlUsdt: '4.5',
                        totalGrossPnlUsdt: '6.0',
                        feeTotalUsdt: '1.5',
                        winCount: '1',
                        lossCount: '1',
                        activeTradeCount: '0',
                        completedTradeCount: '2',
                    },
                ],
            })
            .mockResolvedValueOnce({
                data: {
                    summary: {
                        id: 'session-1',
                        status: 'STOPPED',
                        startReason: 'operator-start',
                    },
                    configSnapshot: {
                        sessionBudgetUsdt: '50',
                        targetProfitUsdt: '10',
                        autoTargetMode: {
                            allowNewSessionStart: true,
                        },
                        liveExecution: {},
                        scan: {},
                    },
                    timelineEventCount: '7',
                    tradeCount: '2',
                },
            })
            .mockResolvedValueOnce({
                data: {
                    items: [
                        {
                            id: 'tl-1',
                            sourceType: 'LIVE_TRADE_EXECUTION_EVENT',
                            eventCategory: 'EXCHANGE',
                            severity: 'ERROR',
                            eventTs: '2026-03-09T00:16:00Z',
                            eventType: 'SAFE_CLOSE_TIMEOUT',
                            summaryPayload: {
                                reasonCode: 'UPSTREAM_TIMEOUT',
                            },
                            debugAvailable: true,
                        },
                    ],
                    total: '2',
                    filteredCount: '1',
                },
            })
            .mockResolvedValueOnce({
                data: {
                    items: [
                        {
                            executionId: 'exec-1',
                            symbol: 'BTCUSDT',
                            side: 'BUY',
                            executionState: 'CLOSED',
                            allocatedBudgetSliceUsdt: '16.67',
                            realizedNetPnlUsdt: '7',
                            outcome: 'WIN',
                        },
                    ],
                    total: '1',
                    activeCount: '0',
                    completedCount: '1',
                },
            })
            .mockResolvedValueOnce({
                data: {
                    trade: {
                        executionId: 'exec-1',
                        symbol: 'BTCUSDT',
                        side: 'BUY',
                        executionState: 'CLOSED',
                        outcome: 'WIN',
                    },
                    execution: {
                        id: 'exec-1',
                        recommendationId: 'rec-1',
                        symbol: 'BTCUSDT',
                        side: 'BUY',
                        executionState: 'CLOSED',
                        events: [],
                    },
                    decisionAudits: [],
                    orders: [],
                    pnlLedgerEntries: [],
                    generatedAt: '2026-03-09T00:20:00Z',
                },
            })
            .mockResolvedValueOnce({
                data: {
                    session: {
                        summary: {
                            id: 'session-1',
                            status: 'STOPPED',
                        },
                        configSnapshot: {
                            autoTargetMode: {},
                            liveExecution: {},
                            scan: {},
                        },
                    },
                    timeline: [],
                    trades: {
                        'exec-1': {
                            trade: {
                                executionId: 'exec-1',
                                symbol: 'BTCUSDT',
                                side: 'BUY',
                                executionState: 'CLOSED',
                                outcome: 'WIN',
                            },
                            execution: {
                                id: 'exec-1',
                                recommendationId: 'rec-1',
                                symbol: 'BTCUSDT',
                                side: 'BUY',
                                executionState: 'CLOSED',
                                events: [],
                            },
                            decisionAudits: [],
                            orders: [],
                            pnlLedgerEntries: [],
                        },
                    },
                    generatedAt: '2026-03-09T00:21:00Z',
                },
            });

        const sessions = await getBudgetTargetAutoExecutionSessions();
        const detail = await getBudgetTargetAutoExecutionSessionDetail('session-1');
        const timeline = await getBudgetTargetAutoExecutionTimeline('session-1', { severity: 'ERROR' });
        const trades = await getBudgetTargetAutoExecutionTrades('session-1', { state: 'COMPLETED' });
        const tradeDetail = await getBudgetTargetAutoExecutionTradeDetail('session-1', 'exec-1');
        const replay = await getBudgetTargetAutoExecutionAuditReplay('session-1');

        expect(sessions[0].winCount).toBe(1);
        expect(detail.configSnapshot.sessionBudgetUsdt).toBe(50);
        expect(detail.timelineEventCount).toBe(7);
        expect(timeline.items[0].severity).toBe('ERROR');
        expect(trades.items[0].allocatedBudgetSliceUsdt).toBe(16.67);
        expect(tradeDetail.trade?.executionId).toBe('exec-1');
        expect(replay.trades['exec-1']?.trade?.symbol).toBe('BTCUSDT');
    });

    it('parses stream events from sse payloads', () => {
        const result = parseBudgetTargetSessionStreamEvent({
            eventId: '12',
            sessionId: 'session-1',
            summary: {
                id: 'session-1',
                status: 'RUNNING',
            },
            timelineItem: {
                id: 'timeline-1',
                sourceType: 'LIVE_TRADE_EXECUTION_EVENT',
                eventCategory: 'EXCHANGE',
                severity: 'INFO',
                eventType: 'ENTRY_SUBMITTED',
                summaryPayload: {
                    exchangeOrderId: 1001,
                },
                debugAvailable: true,
            },
        });

        expect(result.eventId).toBe(12);
        expect(result.summary?.status).toBe('RUNNING');
        expect(result.timelineItem?.eventType).toBe('ENTRY_SUBMITTED');
        expect(result.timelineItem?.summaryPayload.exchangeOrderId).toBe(1001);
    });
});
