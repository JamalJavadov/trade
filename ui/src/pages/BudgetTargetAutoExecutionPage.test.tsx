import React from 'react';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { BudgetTargetAutoExecutionPage } from './BudgetTargetAutoExecutionPage';
import type {
    BudgetTargetAutoExecutionStateDTO,
    BudgetTargetSessionDetailDTO,
    BudgetTargetSessionDTO,
    BudgetTargetSessionSummaryDTO,
    BudgetTargetTradeDetailDTO,
    BudgetTargetTradeHistoryResponseDTO,
    BudgetTargetEventTimelineResponseDTO,
} from '../api/budgetTargetAutoExecutionApi';
import type { LiveTradeExecutionDTO, LiveTradingPreflightDTO } from '../api/liveTradingApi';

const {
    useBudgetTargetAutoExecutionMock,
    usePermissionsMock,
    pushToastMock,
    startSessionMock,
    stopSessionMock,
    loadTradeDetailMock,
    clearTradeDetailMock,
    loadAuditReplayMock,
} = vi.hoisted(() => ({
    useBudgetTargetAutoExecutionMock: vi.fn(),
    usePermissionsMock: vi.fn(),
    pushToastMock: vi.fn(),
    startSessionMock: vi.fn(),
    stopSessionMock: vi.fn(),
    loadTradeDetailMock: vi.fn(),
    clearTradeDetailMock: vi.fn(),
    loadAuditReplayMock: vi.fn(),
}));

vi.mock('../hooks/useBudgetTargetAutoExecution', () => ({
    useBudgetTargetAutoExecution: useBudgetTargetAutoExecutionMock,
}));

vi.mock('../hooks/usePermissions', () => ({
    usePermissions: usePermissionsMock,
}));

vi.mock('../store/toastStore', () => ({
    useToastStore: (selector: (store: { pushToast: typeof pushToastMock }) => unknown) => selector({
        pushToast: pushToastMock,
    }),
}));

vi.mock('../components/Banner', () => ({
    Banner: ({ message }: { message: string }) => <div>{message}</div>,
}));

function buildSession(overrides: Partial<BudgetTargetSessionDTO> = {}): BudgetTargetSessionDTO {
    return {
        id: 'session-1',
        status: 'RUNNING',
        stopReason: null,
        stopReasonMessage: null,
        budgetAmountUsdt: 50,
        targetProfitUsdt: 10,
        completionReason: null,
        sessionBudgetUsdt: 50,
        finalTargetNetProfitUsdt: 10,
        realizedNetPnlUsdt: 4.5,
        unrealizedNetPnlUsdt: 0,
        remainingBankrollUsdt: 45.5,
        maxConcurrentPositions: 3,
        activePositionsCount: 1,
        openedPositionsTotal: 1,
        closedPositionsTotal: 0,
        activeTradeLimit: 3,
        activeTradeCount: 1,
        openedTradeCount: 1,
        pendingScanRunId: null,
        stopRequested: false,
        lastErrorCode: null,
        lastErrorMessage: null,
        failureReasonCode: null,
        failureReasonMessage: null,
        executionFailureCount: 0,
        startedAt: '2026-03-09T00:00:00Z',
        endedAt: null,
        completedAt: null,
        updatedAt: '2026-03-09T00:05:00Z',
        ...overrides,
    };
}

function buildOrder(overrides: Partial<LiveTradeExecutionDTO> = {}): LiveTradeExecutionDTO {
    return {
        id: 'order-1',
        recommendationId: 'rec-1',
        sessionId: 'session-1',
        budgetTargetSessionId: 'session-1',
        symbol: 'BTCUSDT',
        side: 'BUY',
        triggerMode: 'AUTO_SESSION',
        operatorId: 'system',
        traceId: 'trace-1',
        dryRun: false,
        executionState: 'ACTIVE',
        errorCode: null,
        errorMessage: null,
        reservedMarginUsdt: 10,
        realizedNetPnlUsdt: 0,
        unrealizedNetPnlUsdt: null,
        entryPrice: null,
        markPrice: null,
        closeReason: null,
        createdAt: '2026-03-09T00:01:00Z',
        updatedAt: '2026-03-09T00:02:00Z',
        submittedAt: '2026-03-09T00:01:10Z',
        completedAt: null,
        lastReconciledAt: '2026-03-09T00:02:00Z',
        reconcileCount: 1,
        orderRefs: {
            entryClientOrderId: null,
            slClientOrderId: null,
            tpClientOrderId: null,
            emergencyCloseClientOrderId: null,
            entryOrderId: null,
            slOrderId: null,
            tpOrderId: null,
            emergencyCloseOrderId: null,
        },
        payloadSnapshot: {},
        preflight: {},
        entryResponse: {},
        protectionResponse: {},
        exchangeResponse: {},
        events: [],
        ...overrides,
    };
}

function buildHealth(overrides: Partial<LiveTradingPreflightDTO> = {}): LiveTradingPreflightDTO {
    return {
        recommendationId: null,
        symbol: 'BTCUSDT',
        side: null,
        allowed: true,
        executable: true,
        executionEnabled: true,
        checkedAt: '2026-03-09T00:05:00Z',
        runtime: {
            liveExecutionEnabled: true,
            readOnly: false,
            tradingEnabled: true,
            runtimeReady: true,
            recommendationStale: false,
            staleThresholdSeconds: 900,
            recommendationAgeSeconds: null,
            duplicateSubmitBlocked: false,
        },
        localRequest: {
            allowed: true,
            remoteAddress: '127.0.0.1',
            forwardedFor: null,
            origin: 'http://localhost:5173',
            failureReason: null,
        },
        binance: {
            credentialsPresent: true,
            authValid: true,
            credentialSource: 'UI',
            authMode: 'KEY',
            accountInfoReadOk: true,
            accountConfigReadOk: true,
            futuresOrderReadOk: true,
            positionModeReadOk: true,
            ipAllowlistOk: true,
            futuresPermissionOk: true,
            timestampOk: true,
            signingOk: true,
            endpointFamily: 'BINANCE_FUTURES',
            baseUrl: 'https://fapi.binance.com',
            spotBaseUrl: 'https://api.binance.com',
            recvWindowMs: 5000,
            localTimestampMs: null,
            serverTimestampMs: null,
            timestampSkewMs: null,
            requestIpHint: null,
            blockerCode: null,
            blockerMessage: null,
            endpointResults: [],
        },
        exchangeValidation: {
            valid: true,
            markPrice: 100000,
            quantity: 0.01,
            entryNotionalUsdt: 1000,
            tickSize: 0.1,
            stepSize: 0.001,
            minQty: 0.001,
            minNotional: 5,
            slStopPrice: 98000,
            tpStopPrice: 102000,
            leverage: 5,
            marginMode: 'ISOLATED',
            positionMode: 'ONE_WAY',
            failures: [],
        },
        placeability: null,
        placeabilityOk: true,
        blockedReasons: [],
        summary: {
            connectionStatus: 'CONNECTED',
            executableNow: true,
            primaryBlockerCode: null,
            primaryBlockerMessage: null,
            advancedDiagnosticsAvailable: false,
        },
        ...overrides,
    };
}

function buildState(overrides: Partial<BudgetTargetAutoExecutionStateDTO> = {}): BudgetTargetAutoExecutionStateDTO {
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
        latestSession: null,
        primaryBlockedReasonCode: null,
        primaryBlockedReasonMessage: null,
        primaryBlockedReasonSource: null,
        orders: [],
        events: [],
        serverTime: '2026-03-09T00:05:00Z',
        ...overrides,
    };
}

function buildSessionSummary(overrides: Partial<BudgetTargetSessionSummaryDTO> = {}): BudgetTargetSessionSummaryDTO {
    return {
        id: 'session-1',
        status: 'STOPPED',
        startedAt: '2026-03-09T00:00:00Z',
        endedAt: '2026-03-09T00:20:00Z',
        startedBy: 'operator-1',
        startReason: 'operator-start',
        targetSatisfiedAt: '2026-03-09T00:15:00Z',
        budgetAmountUsdt: 50,
        targetProfitUsdt: 10,
        realizedNetPnlUsdt: 4.5,
        totalGrossPnlUsdt: 6,
        feeTotalUsdt: 1.5,
        winCount: 1,
        lossCount: 1,
        activeTradeCount: 0,
        completedTradeCount: 2,
        stopReason: 'TARGET_REACHED',
        mostRecentCriticalError: {
            sourceType: 'LIVE_TRADE_EXECUTION_EVENT',
            eventCategory: 'EXCHANGE',
            eventType: 'SAFE_CLOSE_TIMEOUT',
            code: 'UPSTREAM_TIMEOUT',
            message: 'Emergency close timed out.',
            eventTs: '2026-03-09T00:16:00Z',
            executionId: 'exec-2',
        },
        ...overrides,
    };
}

function buildSessionDetail(overrides: Partial<BudgetTargetSessionDetailDTO> = {}): BudgetTargetSessionDetailDTO {
    return {
        summary: buildSessionSummary(),
        configSnapshot: {
            controlCenterVersion: 12,
            controlCenterUpdatedAt: '2026-03-09T00:00:00Z',
            startedBy: 'operator-1',
            traceId: 'trace-session',
            sessionBudgetUsdt: 50,
            targetProfitUsdt: 10,
            maxConcurrentPositions: 3,
            autoTargetMode: {
                enabled: true,
                armed: false,
                readOnly: false,
                defaultBudgetUsdt: 50,
                defaultTargetProfitUsdt: 10,
                maxConcurrentPositions: 3,
                allowNewSessionStart: true,
                allowCloseAllOnTarget: true,
                killSwitch: false,
                requireBinanceHealthPass: true,
                requireOperatorConfirmationForStop: true,
                sessionTimeoutMinutes: 240,
            },
            liveExecution: {
                readOnly: false,
                enabled: true,
            },
            scan: {
                safeMode: false,
            },
        },
        pendingScanRunId: null,
        traceId: 'trace-session',
        latestCriticalError: {
            sourceType: 'LIVE_TRADE_EXECUTION_EVENT',
            eventCategory: 'EXCHANGE',
            eventType: 'SAFE_CLOSE_TIMEOUT',
            code: 'UPSTREAM_TIMEOUT',
            message: 'Emergency close timed out.',
            eventTs: '2026-03-09T00:16:00Z',
            executionId: 'exec-2',
        },
        lastEventAt: '2026-03-09T00:16:00Z',
        timelineEventCount: 7,
        tradeCount: 2,
        ...overrides,
    };
}

function buildTimeline(overrides: Partial<BudgetTargetEventTimelineResponseDTO> = {}): BudgetTargetEventTimelineResponseDTO {
    return {
        items: [
            {
                id: 'tl-1',
                sourceType: 'LIVE_TRADE_EXECUTION_EVENT',
                eventCategory: 'EXCHANGE',
                severity: 'ERROR',
                eventTs: '2026-03-09T00:16:00Z',
                sessionId: 'session-1',
                executionId: 'exec-2',
                recommendationId: 'rec-2',
                scanRunId: 'scan-1',
                symbol: 'ETHUSDT',
                eventType: 'SAFE_CLOSE_TIMEOUT',
                status: 'RECONCILING',
                reasonCode: 'UPSTREAM_TIMEOUT',
                actor: 'system',
                message: 'Emergency close timed out.',
                summaryPayload: { reasonCode: 'UPSTREAM_TIMEOUT' },
                debugAvailable: true,
            },
            {
                id: 'tl-2',
                sourceType: 'BUDGET_TARGET_SESSION_EVENT',
                eventCategory: 'SESSION',
                severity: 'INFO',
                eventTs: '2026-03-09T00:15:00Z',
                sessionId: 'session-1',
                executionId: null,
                recommendationId: null,
                scanRunId: null,
                symbol: null,
                eventType: 'TARGET_REACHED',
                status: 'STOPPED',
                reasonCode: 'TARGET_REACHED',
                actor: 'system',
                message: 'Final target realized net PnL was reached.',
                summaryPayload: { targetSatisfiedAt: '2026-03-09T00:15:00Z' },
                debugAvailable: true,
            },
        ],
        total: 2,
        filteredCount: 2,
        ...overrides,
    };
}

function buildTradeHistory(overrides: Partial<BudgetTargetTradeHistoryResponseDTO> = {}): BudgetTargetTradeHistoryResponseDTO {
    return {
        items: [
            {
                executionId: 'exec-1',
                recommendationId: 'rec-1',
                scanRunId: 'scan-1',
                symbol: 'BTCUSDT',
                side: 'BUY',
                triggerMode: 'AUTO_SESSION',
                allocatedBudgetSliceUsdt: 16.67,
                reservedMarginUsdt: 15,
                positionSlot: 1,
                openedAt: '2026-03-09T00:02:00Z',
                closedAt: '2026-03-09T00:15:10Z',
                executionState: 'CLOSED',
                openReason: 'Opened after BTC intake passed.',
                closeReason: 'TAKE_PROFIT',
                realizedGrossPnlUsdt: 8,
                realizedFeesUsdt: 1,
                realizedNetPnlUsdt: 7,
                outcome: 'WIN',
                latestCriticalError: null,
            },
            {
                executionId: 'exec-2',
                recommendationId: 'rec-2',
                scanRunId: 'scan-1',
                symbol: 'ETHUSDT',
                side: 'BUY',
                triggerMode: 'AUTO_SESSION',
                allocatedBudgetSliceUsdt: 16.67,
                reservedMarginUsdt: 15,
                positionSlot: 2,
                openedAt: '2026-03-09T00:03:00Z',
                closedAt: '2026-03-09T00:15:30Z',
                executionState: 'FAILED',
                openReason: 'Rejected stale ETH candidate.',
                closeReason: 'STOP_LOSS',
                realizedGrossPnlUsdt: -2,
                realizedFeesUsdt: 0.5,
                realizedNetPnlUsdt: -2.5,
                outcome: 'LOSS',
                latestCriticalError: {
                    sourceType: 'LIVE_TRADE_EXECUTION_EVENT',
                    eventCategory: 'EXCHANGE',
                    eventType: 'SAFE_CLOSE_TIMEOUT',
                    code: 'UPSTREAM_TIMEOUT',
                    message: 'Emergency close timed out.',
                    eventTs: '2026-03-09T00:16:00Z',
                    executionId: 'exec-2',
                },
            },
        ],
        total: 2,
        activeCount: 0,
        completedCount: 2,
        ...overrides,
    };
}

function buildTradeDetail(overrides: Partial<BudgetTargetTradeDetailDTO> = {}): BudgetTargetTradeDetailDTO {
    return {
        trade: buildTradeHistory().items[0],
        execution: buildOrder({
            id: 'exec-1',
            symbol: 'BTCUSDT',
            executionState: 'CLOSED',
            exchangeResponse: { entry: { status: 'FILLED' } },
        }),
        decisionAudits: [
            {
                id: 'decision-1',
                sourceType: 'SESSION_SYMBOL_DECISION_AUDIT',
                eventCategory: 'TRADE',
                severity: 'INFO',
                eventTs: '2026-03-09T00:02:10Z',
                sessionId: 'session-1',
                executionId: 'exec-1',
                recommendationId: 'rec-1',
                scanRunId: 'scan-1',
                symbol: 'BTCUSDT',
                eventType: 'INTAKE_ACCEPTED',
                status: 'RUNNING',
                reasonCode: 'INTAKE_ACCEPTED',
                actor: 'system',
                message: 'Opened after BTC intake passed.',
                summaryPayload: {},
                debugAvailable: true,
            },
        ],
        orders: [
            {
                id: 'order-row-1',
                orderRole: 'ENTRY',
                clientOrderId: 'btc-entry',
                exchangeOrderId: 1001,
                clientAlgoId: null,
                exchangeAlgoId: null,
                requestedQty: 0.01,
                executedQty: 0.01,
                limitPrice: null,
                triggerPrice: null,
                avgFillPrice: 100000,
                orderStatus: 'FILLED',
                requestPayload: { clientOrderId: 'btc-entry' },
                responsePayload: { orderId: 1001 },
                snapshotPayload: { orderRole: 'ENTRY' },
                createdAt: '2026-03-09T00:02:00Z',
                updatedAt: '2026-03-09T00:02:10Z',
            },
        ],
        closure: {
            id: 'closure-1',
            closeReason: 'TAKE_PROFIT',
            closedQty: 0.01,
            closedPrice: 100500,
            closingClientOrderId: 'btc-close',
            closingOrderId: 5001,
            finalPositionSnapshot: { positionAmt: '0' },
            closeResponse: { status: 'FILLED' },
            closedAt: '2026-03-09T00:15:10Z',
        },
        pnlLedgerEntries: [
            {
                id: 'ledger-1',
                eventType: 'REALIZED_GROSS_PNL',
                amountUsdt: 8,
                eventTs: '2026-03-09T00:15:10Z',
                sourceType: 'REALIZED_GROSS_PNL',
                sourceRef: 'gross:btc',
                notes: 'REALIZED_GROSS_PNL',
                before: {},
                after: {},
            },
        ],
        generatedAt: '2026-03-09T00:20:00Z',
        ...overrides,
    };
}

function mockHook(overrides: Record<string, unknown> = {}) {
    useBudgetTargetAutoExecutionMock.mockReturnValue({
        state: buildState(),
        session: null,
        health: buildHealth(),
        sessions: [buildSessionSummary()],
        resolvedSessionId: 'session-1',
        sessionDetail: buildSessionDetail(),
        timeline: buildTimeline(),
        tradeHistory: buildTradeHistory(),
        tradeDetail: null,
        tradeDetailLoading: false,
        auditReplay: null,
        auditReplayLoading: false,
        loading: false,
        refreshing: false,
        auditLoading: false,
        auditRefreshing: false,
        updating: false,
        reconnecting: false,
        error: null,
        lastUpdatedAt: '2026-03-09T00:20:00Z',
        sessionIsActive: false,
        activeOrders: [],
        completedOrders: [],
        targetProgressPct: 0,
        remainingToTargetUsdt: 10,
        primaryBlockedReason: {
            code: null,
            message: 'No blocker reported.',
            source: 'none',
        },
        auditTransportMode: 'sse',
        refresh: vi.fn(),
        refreshAudit: vi.fn(),
        startSession: startSessionMock,
        stopSession: stopSessionMock,
        loadTradeDetail: loadTradeDetailMock,
        clearTradeDetail: clearTradeDetailMock,
        loadAuditReplay: loadAuditReplayMock,
        ...overrides,
    });
}

function renderPage(initialEntry = '/auto-session?sessionId=session-1') {
    return render(
        <MemoryRouter initialEntries={[initialEntry]}>
            <BudgetTargetAutoExecutionPage />
        </MemoryRouter>,
    );
}

describe('BudgetTargetAutoExecutionPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        usePermissionsMock.mockReturnValue({
            can: vi.fn().mockReturnValue(true),
        });
        mockHook();
    });

    afterEach(() => {
        cleanup();
    });

    it('starts a session from the idle state with entered budget and target', async () => {
        const user = userEvent.setup();
        renderPage('/auto-session');

        const budgetInput = screen.getByLabelText(/Budget \(USDT\)/i);
        const targetInput = screen.getByLabelText(/Final Target \(USDT\)/i);

        await user.clear(budgetInput);
        await user.type(budgetInput, '75');
        await user.clear(targetInput);
        await user.type(targetInput, '12');
        await user.click(screen.getByRole('switch', { name: 'Budget Target Auto-Execution toggle' }));

        expect(startSessionMock).toHaveBeenCalledWith({
            budgetAmountUsdt: 75,
            targetProfitUsdt: 12,
        });
        expect(screen.getByText('Audit, Replay, and Trade Drill-Down')).toBeInTheDocument();
    });

    it('renders audit summary cards timeline filters and trade drill-down', async () => {
        const user = userEvent.setup();
        mockHook({
            session: buildSession({
                status: 'RUNNING',
                activeTradeCount: 1,
            }),
            sessionIsActive: true,
            state: buildState({
                config: {
                    ...buildState().config,
                    armed: true,
                },
                activeSession: buildSession(),
            }),
            tradeDetail: buildTradeDetail(),
            targetProgressPct: 45,
            remainingToTargetUsdt: 5.5,
        });

        renderPage();
        await user.click(screen.getByText('Audit, Replay, and Trade Drill-Down'));

        expect(screen.getByText('Session Audit Report')).toBeInTheDocument();
        expect(screen.getByText('+4.50 USDT')).toBeInTheDocument();
        expect(screen.getByText('+6.00 USDT')).toBeInTheDocument();
        expect(screen.getByText('1 / 1')).toBeInTheDocument();
        expect(screen.getByText('SAFE_CLOSE_TIMEOUT')).toBeInTheDocument();
        expect(screen.getAllByText('Opened after BTC intake passed.').length).toBeGreaterThan(0);
        expect(screen.getByText('Trade Drill-Down')).toBeInTheDocument();
        expect(screen.getByText('Exchange Response')).toBeInTheDocument();

        await user.selectOptions(screen.getByLabelText('Severity'), 'ERROR');
        expect(screen.getAllByText('Emergency close timed out.').length).toBeGreaterThan(0);
        expect(screen.queryByText('Final target realized net PnL was reached.')).not.toBeInTheDocument();
    });

    it('requires confirmation before graceful stop when configured', async () => {
        const user = userEvent.setup();
        const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
        const session = buildSession();
        mockHook({
            state: buildState({
                config: {
                    ...buildState().config,
                    armed: true,
                    requireOperatorConfirmationForStop: true,
                },
                activeSession: session,
            }),
            session,
            sessionIsActive: true,
        });

        renderPage();
        await user.click(screen.getByRole('switch', { name: 'Budget Target Auto-Execution toggle' }));

        expect(confirmSpy).toHaveBeenCalledTimes(1);
        expect(stopSessionMock).toHaveBeenCalledWith({ confirmStop: true });
        confirmSpy.mockRestore();
    });

    it('requests replay payloads and trade detail actions from the hook', async () => {
        const user = userEvent.setup();
        renderPage();

        await user.click(screen.getByText('Audit, Replay, and Trade Drill-Down'));
        await user.click(screen.getByRole('button', { name: 'Load replay payload' }));
        expect(loadAuditReplayMock).toHaveBeenCalledTimes(1);

        const tradeSection = screen.getByText('Trade History').closest('section');
        expect(tradeSection).not.toBeNull();
        await user.click(within(tradeSection as HTMLElement).getAllByRole('button', { name: 'Inspect' })[0]);
        expect(loadTradeDetailMock).toHaveBeenCalledWith('exec-1');
    });

    it('blocks session start when budget or target are invalid', async () => {
        const user = userEvent.setup();
        renderPage('/auto-session');

        const budgetInput = screen.getByLabelText(/Budget \(USDT\)/i);
        const targetInput = screen.getByLabelText(/Final Target \(USDT\)/i);
        const toggle = screen.getByRole('switch', { name: 'Budget Target Auto-Execution toggle' });

        await user.clear(budgetInput);
        await user.type(budgetInput, '0');
        await user.clear(targetInput);
        await user.type(targetInput, '0');

        expect(toggle).toBeDisabled();
        expect(screen.getByText('Budget and final target must both be greater than 0.')).toBeInTheDocument();
    });

    it('renders the running session state target progress and primary blocked reason', () => {
        const session = buildSession({
            status: 'RUNNING',
            activeTradeCount: 1,
            targetProfitUsdt: 10,
            realizedNetPnlUsdt: 4,
        });
        mockHook({
            state: buildState({
                config: {
                    ...buildState().config,
                    armed: true,
                },
                activeSession: session,
            }),
            session,
            sessionIsActive: true,
            targetProgressPct: 40,
            remainingToTargetUsdt: 6,
            primaryBlockedReason: {
                code: 'ACTIVE_LIMIT_REACHED',
                message: 'Maximum of 3 active positions are already open.',
                source: 'event',
            },
        });

        renderPage('/auto-session');

        expect(screen.getAllByText('RUNNING').length).toBeGreaterThan(0);
        expect(screen.getByText('40%')).toBeInTheDocument();
        expect(screen.getByText('Realized 4.00 USDT of 10.00 USDT')).toBeInTheDocument();
        expect(screen.getByText('Maximum of 3 active positions are already open.')).toBeInTheDocument();
        expect(screen.getByText('1 / 3')).toBeInTheDocument();
    });

    it('falls back to runtime order lists when audit permission is missing', () => {
        usePermissionsMock.mockReturnValue({
            can: vi.fn((permission: string) => permission === 'live.execution.auto_session.manage'),
        });
        mockHook({
            activeOrders: [buildOrder({
                unrealizedNetPnlUsdt: 1.75,
                markPrice: 101250.5,
                entryPrice: 100000,
                lastReconciledAt: '2026-03-09T00:02:30Z',
            })],
            completedOrders: [
                buildOrder({
                    id: 'order-2',
                    symbol: 'ETHUSDT',
                    executionState: 'CLOSED',
                    realizedNetPnlUsdt: -2.25,
                    closeReason: 'STOP_LOSS',
                    completedAt: '2026-03-09T00:05:00Z',
                }),
            ],
        });

        renderPage('/auto-session');

        expect(screen.getByText('Audit Access Required')).toBeInTheDocument();
        expect(screen.getByText('Active / Open Trades')).toBeInTheDocument();
        expect(screen.getByText('Completed Trades / Results')).toBeInTheDocument();
        expect(screen.getByText('BTCUSDT')).toBeInTheDocument();
        expect(screen.getByText('ACTIVE')).toBeInTheDocument();
        expect(screen.getByText('+1.75 USDT')).toBeInTheDocument();
        expect(screen.getByText('Mark 101250.5')).toBeInTheDocument();
        expect(screen.getByText('Entry 100000')).toBeInTheDocument();
        expect(screen.getByText('ETHUSDT')).toBeInTheDocument();
        expect(screen.getByText('-2.25 USDT')).toBeInTheDocument();
        expect(screen.getByText('STOP_LOSS')).toBeInTheDocument();
    });

    it('shows loading and error states before runtime data is available', () => {
        mockHook({
            state: null,
            loading: true,
        });
        const { rerender } = render(
            <MemoryRouter initialEntries={['/auto-session']}>
                <BudgetTargetAutoExecutionPage />
            </MemoryRouter>,
        );

        expect(screen.getByText('Loading backend state...')).toBeInTheDocument();

        mockHook({
            state: null,
            loading: false,
            error: {
                errorCode: 'STATE_UNAVAILABLE',
                message: 'Backend offline.',
            },
        });
        rerender(
            <MemoryRouter initialEntries={['/auto-session']}>
                <BudgetTargetAutoExecutionPage />
            </MemoryRouter>,
        );

        expect(screen.getByText('STATE_UNAVAILABLE: Backend offline.')).toBeInTheDocument();
    });
});
