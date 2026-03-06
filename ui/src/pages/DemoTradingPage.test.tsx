import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DemoTradingPage } from './DemoTradingPage';

const demoApiMock = vi.hoisted(() => ({
    getStatus: vi.fn(),
    enable: vi.fn(),
    disable: vi.fn(),
    runOnce: vi.fn(),
    reset: vi.fn(),
    getTrades: vi.fn(),
    getTrade: vi.fn(),
    getOpenTrades: vi.fn(),
    getAnalytics: vi.fn(),
    getLatestSuggestions: vi.fn(),
    acceptBatch: vi.fn(),
    rejectBatch: vi.fn(),
    getAiModels: vi.fn(),
    updateAiModels: vi.fn(),
    testAiModels: vi.fn(),
}));
const permissionsHookMock = vi.hoisted(() => ({
    canMock: vi.fn(),
}));

vi.mock('../api/demoApi', () => ({
    demoApi: demoApiMock,
}));

vi.mock('../hooks/usePermissions', () => ({
    usePermissions: () => ({
        can: permissionsHookMock.canMock,
    }),
}));

vi.mock('../components/demo/DemoAccountCard', () => ({
    DemoAccountCard: () => <div data-testid="demo-account-card">Demo Account Card</div>,
}));

vi.mock('../components/demo/DemoAnalyticsCard', () => ({
    DemoAnalyticsCard: () => <div data-testid="demo-analytics-card">Demo Analytics Card</div>,
}));

function createStatus(enabled = true) {
    return {
        enabled,
        running: enabled,
        intervalMinutes: 15,
        maxOpenPositions: 1,
        account: {
            balanceUsdt: 1000,
            equityUsdt: 1005,
        },
        openPositionsCount: 0,
        lastDemoRunStatus: 'IDLE',
        lastDemoTradeSummary: null,
        lastOpenTrade: null,
        lastClosedTrade: null,
        winRate: null,
    };
}

function createSuggestions(proposed: boolean) {
    if (!proposed) {
        return {
            batch: null,
            items: [],
            activeConfigVersion: {
                id: 'cfg-1',
                version: 1,
                createdAt: '2026-03-01T00:00:00Z',
                active: true,
                configJson: '{}',
                changeReason: 'seed',
            },
        };
    }

    return {
        batch: {
            id: 'batch-1',
            createdAt: '2026-03-01T00:00:00Z',
            basedOnLastNTrades: 10,
            status: 'PROPOSED',
            summary: 'Proposed demo tuning updates.',
            model: 'model',
            promptJson: '{}',
            responseJson: '{}',
            errorJson: null,
            acceptedAt: null,
            acceptedBy: null,
            rejectedAt: null,
            rejectReason: null,
            failedAt: null,
            errorCode: null,
            traceId: null,
            latencyMs: null,
            callStatus: null,
        },
        items: [
            {
                batchId: 'batch-1',
                key: 'management.timeStopMinutes',
                proposedValue: '75',
                reason: 'Reduce long tail losses.',
                impactHypothesis: 'Lower average hold time.',
                riskOfChange: 'MEDIUM',
                status: 'PROPOSED',
            },
        ],
        activeConfigVersion: {
            id: 'cfg-1',
            version: 1,
            createdAt: '2026-03-01T00:00:00Z',
            active: true,
            configJson: '{}',
            changeReason: 'seed',
        },
    };
}

function createAnalytics() {
    return {
        lookback: 10,
        generatedAt: '2026-03-01T00:00:00Z',
        metrics: {
            winRate: 0.5,
            avgWinR: 2.3,
            expectancyR: 0.4,
            profitFactor: 1.2,
            maxDrawdownPct: 0.1,
            avgHoldMinutes: 42,
            closeReasonDistribution: { TP1: 2, SL: 1 },
        },
        cohorts: {
            side: [
                { bucket: 'LONG', count: 2, wins: 1, losses: 1, winRate: 0.5, expectancyR: 0.2 },
                { bucket: 'SHORT', count: 1, wins: 1, losses: 0, winRate: 1, expectancyR: 1.1 },
            ],
        },
        topFailurePatterns: [],
        activeConfigVersion: {
            id: 'cfg-1',
            version: 1,
            createdAt: '2026-03-01T00:00:00Z',
            active: true,
            configJson: '{}',
            changeReason: 'seed',
        },
    };
}

function setupApiDefaults() {
    demoApiMock.getStatus.mockResolvedValue(createStatus(true));
    demoApiMock.enable.mockResolvedValue({ message: 'enabled', running: true });
    demoApiMock.disable.mockResolvedValue({ message: 'disabled', running: false });
    demoApiMock.runOnce.mockResolvedValue({ message: 'ran', running: true });
    demoApiMock.reset.mockResolvedValue({ message: 'reset', running: false });
    demoApiMock.getTrades.mockResolvedValue({
        limit: 20,
        offset: 0,
        total: 0,
        trades: [],
    });
    demoApiMock.getTrade.mockResolvedValue({
        id: 'trade-1',
        createdAt: '2026-03-01T00:00:00Z',
        openedAt: '2026-03-01T00:00:00Z',
        closedAt: null,
        symbol: 'BTCUSDT',
        side: 'LONG',
        leverage: 5,
        qty: 0.1,
        remainingQty: 0.1,
        entryPrice: 50000,
        slPrice: 49000,
        currentSlPrice: 49000,
        tp1Price: 51000,
        tp2Price: 52000,
        tp3Price: 53000,
        workingType: 'MARK_PRICE',
        status: 'OPEN',
        closeReason: null,
        stage: 0,
        riskUsdtInitial: 10,
        realizedPnlUsdt: 0,
        entryFeeUsdt: 0.1,
        exitFeeUsdt: 0,
        totalFeesUsdt: 0.1,
        lastMarkPrice: 50010,
        pnlUsdt: 0,
        rMultiple: 0,
        snapshotJson: '{}',
    });
    demoApiMock.getOpenTrades.mockResolvedValue({
        limit: 20,
        offset: 0,
        total: 0,
        trades: [],
    });
    demoApiMock.getAnalytics.mockResolvedValue(createAnalytics());
    demoApiMock.getLatestSuggestions.mockResolvedValue(createSuggestions(false));
    demoApiMock.acceptBatch.mockResolvedValue({ message: 'accepted', running: true });
    demoApiMock.rejectBatch.mockResolvedValue({ message: 'rejected', running: true });
    demoApiMock.getAiModels.mockResolvedValue({
        mode: 'DEMO',
        controlsEnabled: false,
        allowlist: ['arcee-ai/trinity-large-preview:free'],
        tasks: [{
            taskType: 'SUGGESTION_BATCH',
            primaryModel: 'arcee-ai/trinity-large-preview:free',
            fallbackModels: [],
            lastCall: null,
        }],
    });
    demoApiMock.updateAiModels.mockResolvedValue({
        mode: 'DEMO',
        controlsEnabled: false,
        allowlist: ['arcee-ai/trinity-large-preview:free'],
        tasks: [{
            taskType: 'SUGGESTION_BATCH',
            primaryModel: 'arcee-ai/trinity-large-preview:free',
            fallbackModels: [],
            lastCall: null,
        }],
    });
    demoApiMock.testAiModels.mockResolvedValue({
        mode: 'DEMO',
        taskType: 'SUGGESTION_BATCH',
        ok: true,
        simulated: true,
        traceId: 'test-1',
        latencyMs: 0,
        modelUsed: 'arcee-ai/trinity-large-preview:free',
        payload: { message: 'Dummy AI model test response' },
    });
}

function renderDemo(initialPath = '/demo') {
    return render(
        <MemoryRouter initialEntries={[initialPath]}>
            <Routes>
                <Route path="/demo" element={<DemoTradingPage />} />
                <Route path="/demo/trades/:id" element={<DemoTradingPage />} />
            </Routes>
        </MemoryRouter>,
    );
}

function createDeferred<T>() {
    let resolve: (value: T) => void = () => {};
    let reject: (reason?: unknown) => void = () => {};
    const promise = new Promise<T>((res, rej) => {
        resolve = res;
        reject = rej;
    });
    return { promise, resolve, reject };
}

describe('DemoTradingPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        setupApiDefaults();
        permissionsHookMock.canMock.mockImplementation(() => true);
    });

    afterEach(() => {
        cleanup();
        vi.useRealTimers();
    });

    it('requires typing RESET before allowing reset confirmation', async () => {
        const user = userEvent.setup();
        renderDemo();

        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Reset' })).toBeInTheDocument();
        });

        await user.click(screen.getByRole('button', { name: 'Reset' }));

        const confirmButton = screen.getByRole('button', { name: 'Confirm Reset' });
        expect(confirmButton).toBeDisabled();

        await user.type(screen.getByPlaceholderText('Type RESET'), 'RESET');
        expect(confirmButton).toBeEnabled();
    });

    it('opens accept confirmation modal and disables confirm while accept is in flight', async () => {
        const user = userEvent.setup();
        const deferred = createDeferred<{ message: string; running: boolean }>();

        demoApiMock.getLatestSuggestions.mockResolvedValue(createSuggestions(true));
        demoApiMock.acceptBatch.mockReturnValue(deferred.promise);

        renderDemo();

        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Accept' })).toBeInTheDocument();
        });

        await user.click(screen.getByRole('button', { name: 'Accept' }));
        await user.click(screen.getByRole('button', { name: 'Accept & Activate' }));

        expect(demoApiMock.acceptBatch).toHaveBeenCalledWith('batch-1');
        expect(screen.getByRole('button', { name: 'Accepting...' })).toBeDisabled();

        deferred.resolve({ message: 'accepted', running: true });
        await waitFor(() => {
            expect(screen.queryByRole('button', { name: 'Accepting...' })).not.toBeInTheDocument();
        });
    });

    it('starts polling while enabled and stops polling after unmount', async () => {
        vi.useFakeTimers();
        const { unmount } = renderDemo();

        await act(async () => {
            await Promise.resolve();
        });
        expect(demoApiMock.getStatus).toHaveBeenCalled();

        const initialCalls = demoApiMock.getStatus.mock.calls.length;

        await act(async () => {
            vi.advanceTimersByTime(5000);
            await Promise.resolve();
        });

        expect(demoApiMock.getStatus.mock.calls.length).toBeGreaterThan(initialCalls);

        const callsAfterTick = demoApiMock.getStatus.mock.calls.length;
        unmount();

        await act(async () => {
            vi.advanceTimersByTime(15000);
            await Promise.resolve();
        });

        expect(demoApiMock.getStatus).toHaveBeenCalledTimes(callsAfterTick);
    });

    it('shows a banner when backend status request fails and page remains rendered', async () => {
        demoApiMock.getStatus.mockRejectedValueOnce(new Error('Backend down'));

        renderDemo();

        await waitFor(() => {
            expect(screen.getByText(/INTERNAL: Backend down/i)).toBeInTheDocument();
        });

        expect(screen.getByRole('heading', { name: 'Demo Trading' })).toBeInTheDocument();
    });

    it('disables gated demo controls and ai actions when operator permissions are denied', async () => {
        const user = userEvent.setup();
        permissionsHookMock.canMock.mockImplementation((permissionKey: string) => ![
            'demo.enable_disable',
            'demo.reset',
            'ai.suggestions.accept_reject',
        ].includes(permissionKey));
        demoApiMock.getLatestSuggestions.mockResolvedValue(createSuggestions(true));

        renderDemo();

        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Enable' })).toBeInTheDocument();
        });

        const enableButton = screen.getByRole('button', { name: 'Enable' });
        const resetButton = screen.getByRole('button', { name: 'Reset' });
        const acceptButton = screen.getByRole('button', { name: 'Accept' });
        const rejectButton = screen.getByRole('button', { name: 'Reject' });

        expect(enableButton).toBeDisabled();
        expect(enableButton).toHaveAttribute('title', 'Disabled by operator permission: demo.enable_disable');
        expect(resetButton).toBeDisabled();
        expect(resetButton).toHaveAttribute('title', 'Disabled by operator permission: demo.reset');
        expect(acceptButton).toBeDisabled();
        expect(acceptButton).toHaveAttribute('title', 'Disabled by operator permission: ai.suggestions.accept_reject');
        expect(rejectButton).toBeDisabled();
        expect(rejectButton).toHaveAttribute('title', 'Disabled by operator permission: ai.suggestions.accept_reject');

        await user.click(acceptButton);
        expect(screen.queryByText('Accept Demo AI Suggestions')).not.toBeInTheDocument();
    });
});
