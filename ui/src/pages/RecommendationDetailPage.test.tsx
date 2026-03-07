import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { RecommendationDetailPage } from './RecommendationDetailPage';
import type { RecommendationDTO } from '../api/client';
import type { LiveTradeExecutionDTO, LiveTradingPreflightDTO } from '../api/liveTradingApi';

const {
    getRecommendationMock,
    usePermissionsMock,
    useRecommendationExecutionMock,
    executeLiveMock,
} = vi.hoisted(() => ({
    getRecommendationMock: vi.fn(),
    usePermissionsMock: vi.fn(),
    useRecommendationExecutionMock: vi.fn(),
    executeLiveMock: vi.fn(),
}));

vi.mock('../api/client', () => ({
    getRecommendation: getRecommendationMock,
}));

vi.mock('../hooks/usePermissions', () => ({
    usePermissions: usePermissionsMock,
}));

vi.mock('../hooks/useRecommendationExecution', () => ({
    useRecommendationExecution: useRecommendationExecutionMock,
}));

vi.mock('../components/JsonBlock', () => ({
    JsonBlock: ({ label }: { label: string }) => <div>{label}</div>,
}));

vi.mock('../components/FeedbackForm', () => ({
    FeedbackForm: () => <div>feedback-form</div>,
}));

vi.mock('../components/BinanceFillGuide', () => ({
    BinanceFillGuide: () => <div>fill-guide</div>,
}));

vi.mock('../components/Banner', () => ({
    Banner: ({ message }: { message: string }) => <div>{message}</div>,
}));

vi.mock('../components/LiveExecutionConfirmModal', () => ({
    LiveExecutionConfirmModal: ({
        isOpen,
        onConfirm,
        onCancel,
        loading,
    }: {
        isOpen: boolean;
        onConfirm: () => void;
        onCancel: () => void;
        loading: boolean;
    }) => isOpen ? (
        <div>
            <p>confirm-live-modal</p>
            <button onClick={onConfirm}>{loading ? 'Submitting...' : 'Confirm live execution'}</button>
            <button onClick={onCancel}>Cancel live execution</button>
        </div>
    ) : null,
}));

vi.mock('../store/journalStore', () => ({
    journalStore: {
        list: vi.fn(() => []),
        markAsOpen: vi.fn(),
        updateFeedbackStatus: vi.fn(),
    },
}));

const baseRecommendation: RecommendationDTO = {
    id: 'rec-1',
    scanRunId: 'scan-1',
    symbol: 'BTCUSDT',
    side: 'BUY',
    rationaleText: 'Test recommendation',
    confidenceScore: 2.4,
    createdAt: '2026-03-07T09:00:00Z',
    status: 'NEW',
    leverageRecommendation: 5,
    positionMode: 'ONE_WAY',
    marginMode: 'ISOLATED',
    entryOrder: {
        symbol: 'BTCUSDT',
        side: 'BUY',
        type: 'MARKET',
        quantity: 0.01,
    },
    slOrder: {
        symbol: 'BTCUSDT',
        side: 'SELL',
        type: 'STOP_MARKET',
        stopPrice: 98000,
    },
    tpOrder: {
        symbol: 'BTCUSDT',
        side: 'SELL',
        type: 'TAKE_PROFIT_MARKET',
        stopPrice: 104000,
    },
};

function buildPreflight(overrides: Partial<LiveTradingPreflightDTO> = {}): LiveTradingPreflightDTO {
    return {
        recommendationId: 'rec-1',
        symbol: 'BTCUSDT',
        side: 'BUY',
        allowed: true,
        executable: true,
        executionEnabled: true,
        checkedAt: '2026-03-07T09:00:05Z',
        runtime: {
            liveExecutionEnabled: true,
            readOnly: false,
            tradingEnabled: true,
            runtimeReady: true,
            recommendationStale: false,
            staleThresholdSeconds: 900,
            recommendationAgeSeconds: 30,
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
            localTimestampMs: 1_762_000_000_000,
            serverTimestampMs: 1_762_000_000_100,
            timestampSkewMs: 100,
            requestIpHint: null,
            blockerCode: null,
            blockerMessage: null,
            endpointResults: [],
        },
        exchangeValidation: {
            valid: true,
            markPrice: 100500,
            quantity: 0.01,
            entryNotionalUsdt: 1005,
            tickSize: 0.1,
            stepSize: 0.001,
            minQty: 0.001,
            minNotional: 5,
            slStopPrice: 98000,
            tpStopPrice: 104000,
            leverage: 5,
            marginMode: 'ISOLATED',
            positionMode: 'ONE_WAY',
            failures: [],
        },
        placeability: null,
        placeabilityOk: true,
        blockedReasons: [],
        ...overrides,
    };
}

function buildExecution(overrides: Partial<LiveTradeExecutionDTO> = {}): LiveTradeExecutionDTO {
    return {
        id: 'exec-1',
        recommendationId: 'rec-1',
        symbol: 'BTCUSDT',
        side: 'BUY',
        triggerMode: 'MANUAL_BUTTON',
        operatorId: 'local-operator',
        traceId: 'trace-1',
        dryRun: false,
        executionState: 'RECONCILED',
        errorCode: null,
        errorMessage: null,
        createdAt: '2026-03-07T09:01:00Z',
        updatedAt: '2026-03-07T09:01:05Z',
        submittedAt: '2026-03-07T09:01:01Z',
        completedAt: '2026-03-07T09:01:05Z',
        lastReconciledAt: '2026-03-07T09:01:05Z',
        reconcileCount: 1,
        orderRefs: {
            entryClientOrderId: 'entry-1',
            slClientOrderId: 'sl-1',
            tpClientOrderId: 'tp-1',
            emergencyCloseClientOrderId: null,
            entryOrderId: 101,
            slOrderId: 102,
            tpOrderId: 103,
            emergencyCloseOrderId: null,
        },
        payloadSnapshot: {},
        preflight: {},
        exchangeResponse: {},
        events: [
            {
                id: 'evt-1',
                eventType: 'ENTRY_SUBMITTED',
                eventStatus: 'ENTRY_SUBMITTED',
                message: 'Entry order submitted to Binance.',
                errorCode: null,
                payload: {},
                createdAt: '2026-03-07T09:01:02Z',
            },
        ],
        ...overrides,
    };
}

function renderPage() {
    return render(
        <MemoryRouter initialEntries={['/recommendation/rec-1']}>
            <Routes>
                <Route path="/recommendation/:id" element={<RecommendationDetailPage />} />
            </Routes>
        </MemoryRouter>,
    );
}

describe('RecommendationDetailPage live execution panel', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        getRecommendationMock.mockResolvedValue(baseRecommendation);
        usePermissionsMock.mockReturnValue({
            loading: false,
            can: vi.fn(() => true),
        });
        useRecommendationExecutionMock.mockReturnValue({
            preflight: buildPreflight(),
            execution: null,
            history: [],
            loading: false,
            preflightLoading: false,
            historyLoading: false,
            executing: false,
            actionError: null,
            refresh: vi.fn().mockResolvedValue(undefined),
            executeLive: executeLiveMock,
            clearActionError: vi.fn(),
        });
        executeLiveMock.mockResolvedValue(buildExecution());
    });

    afterEach(() => {
        cleanup();
    });

    it('disables the live execution button and shows exact blocked reasons', async () => {
        useRecommendationExecutionMock.mockReturnValue({
            preflight: buildPreflight({
                allowed: false,
                executable: false,
                runtime: {
                    liveExecutionEnabled: true,
                    readOnly: true,
                    tradingEnabled: false,
                    runtimeReady: false,
                    recommendationStale: false,
                    staleThresholdSeconds: 900,
                    recommendationAgeSeconds: 30,
                    duplicateSubmitBlocked: false,
                },
                blockedReasons: [
                    {
                        code: 'BOT_READ_ONLY',
                        message: 'Trading is disabled. Bot is in READ-ONLY mode.',
                        source: 'runtime',
                        details: {},
                    },
                ],
            }),
            execution: null,
            history: [],
            loading: false,
            preflightLoading: false,
            historyLoading: false,
            executing: false,
            actionError: null,
            refresh: vi.fn().mockResolvedValue(undefined),
            executeLive: executeLiveMock,
            clearActionError: vi.fn(),
        });

        renderPage();

        expect(await screen.findByText('Real Binance Execution')).toBeInTheDocument();
        expect(screen.getByText('Blocked Reasons')).toBeInTheDocument();
        expect(screen.getByText('BOT_READ_ONLY')).toBeInTheDocument();
        expect(screen.getAllByText('READ ONLY').length).toBeGreaterThan(0);

        const button = screen.getByRole('button', { name: 'Open Real Order on Binance' });
        expect(button).toBeDisabled();
        expect(screen.getByText(/Execute button disabled: Trading is disabled\. Bot is in READ-ONLY mode\./)).toBeInTheDocument();
    });

    it('opens a confirmation modal and submits only after explicit confirm', async () => {
        const user = userEvent.setup();
        renderPage();

        expect(await screen.findByText('Real Binance Execution')).toBeInTheDocument();

        const button = screen.getByRole('button', { name: 'Open Real Order on Binance' });
        expect(button).toBeEnabled();

        await user.click(button);
        expect(screen.getByText('confirm-live-modal')).toBeInTheDocument();

        await user.click(screen.getByRole('button', { name: 'Confirm live execution' }));

        await waitFor(() => {
            expect(executeLiveMock).toHaveBeenCalledWith(expect.objectContaining({
                clientRequestId: expect.any(String),
                operatorNote: undefined,
            }));
        });
    });

    it('renders execution success and structured action errors from the hook state', async () => {
        useRecommendationExecutionMock.mockReturnValue({
            preflight: buildPreflight(),
            execution: buildExecution(),
            history: [buildExecution()],
            loading: false,
            preflightLoading: false,
            historyLoading: false,
            executing: false,
            actionError: {
                errorCode: 'BINANCE_REJECTED',
                message: 'Exchange rejected the request.',
                traceId: 'trace-err',
                fieldErrors: {},
            },
            refresh: vi.fn().mockResolvedValue(undefined),
            executeLive: executeLiveMock,
            clearActionError: vi.fn(),
        });

        renderPage();

        expect(await screen.findByText('Latest Execution Attempt')).toBeInTheDocument();
        expect(screen.getAllByText('RECONCILED').length).toBeGreaterThan(0);
        expect(screen.getByText(/BINANCE_REJECTED: Exchange rejected the request/)).toBeInTheDocument();
        expect(screen.getByText('Execution Timeline')).toBeInTheDocument();
    });

    it('keeps the real-order button visible but disabled when live execution capability is off', async () => {
        useRecommendationExecutionMock.mockReturnValue({
            preflight: buildPreflight({
                allowed: false,
                executable: false,
                executionEnabled: false,
                runtime: {
                    liveExecutionEnabled: false,
                    readOnly: false,
                    tradingEnabled: true,
                    runtimeReady: false,
                    recommendationStale: false,
                    staleThresholdSeconds: 900,
                    recommendationAgeSeconds: 30,
                    duplicateSubmitBlocked: false,
                },
                blockedReasons: [
                    {
                        code: 'LIVE_EXECUTION_DISABLED',
                        message: 'Manual Binance execution is disabled in Control Center.',
                        source: 'runtime',
                        details: {},
                    },
                ],
            }),
            execution: null,
            history: [],
            loading: false,
            preflightLoading: false,
            historyLoading: false,
            executing: false,
            actionError: null,
            refresh: vi.fn().mockResolvedValue(undefined),
            executeLive: executeLiveMock,
            clearActionError: vi.fn(),
        });

        renderPage();

        expect(await screen.findByText('Real Binance Execution')).toBeInTheDocument();
        const button = screen.getByRole('button', { name: 'Open Real Order on Binance' });
        expect(button).toBeDisabled();
        expect(screen.getAllByText(/Manual Binance execution is disabled in Control Center\./).length).toBeGreaterThan(0);
    });
});
