import { describe, it, expect, vi, beforeEach } from 'vitest';
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

import { demoApi } from './demoApi';

describe('demoApi', () => {
    beforeEach(() => {
        getMock.mockReset();
        postMock.mockReset();
    });

    it('calls getStatus with demo status endpoint', async () => {
        getMock.mockResolvedValue({ data: { enabled: true } });

        await demoApi.getStatus();

        expect(getMock).toHaveBeenCalledWith('/api/v1/demo-trading/status');
    });

    it('normalizes legacy rmultiple fields from demo payloads', async () => {
        getMock.mockResolvedValueOnce({
            data: {
                enabled: true,
                running: true,
                intervalMinutes: 15,
                maxOpenPositions: 1,
                account: null,
                openPositionsCount: 0,
                closedTradesCount: 1,
                lastDemoRunStatus: 'FINISHED',
                cycleCountTotal: 1,
                cycleCountFinished: 1,
                cycleCountFailed: 0,
                cycleRunning: false,
                workflowPhase: 'IDLE',
                lastDemoTradeSummary: {
                    id: 'trade-1',
                    status: 'CLOSED',
                    side: 'LONG',
                    symbol: 'BTCUSDT',
                    openedAt: null,
                    closedAt: null,
                    closeReason: null,
                    stage: 3,
                    remainingQty: 0,
                    pnlUsdt: 12.5,
                    rmultiple: 2.5,
                },
                lastOpenTrade: null,
                lastClosedTrade: null,
                winRate: 0.5,
            },
        });
        getMock.mockResolvedValueOnce({
            data: {
                id: 'trade-1',
                createdAt: null,
                openedAt: null,
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
                tp2Price: null,
                tp3Price: null,
                workingType: 'MARK_PRICE',
                status: 'OPEN',
                closeReason: null,
                stage: 1,
                riskUsdtInitial: 10,
                realizedPnlUsdt: 0,
                entryFeeUsdt: 0.1,
                exitFeeUsdt: 0,
                totalFeesUsdt: 0.1,
                lastMarkPrice: 50010,
                pnlUsdt: 0,
                rmultiple: 1.75,
                snapshotJson: '{}',
            },
        });

        const status = await demoApi.getStatus();
        const trade = await demoApi.getTrade('trade-1');

        expect(status.lastDemoTradeSummary?.rMultiple).toBe(2.5);
        expect(trade.rMultiple).toBe(1.75);
    });

    it('calls enable/disable/runOnce endpoints', async () => {
        postMock.mockResolvedValue({ data: { message: 'ok', running: true } });

        await demoApi.enable();
        await demoApi.disable();
        await demoApi.runOnce();

        expect(postMock).toHaveBeenNthCalledWith(1, '/api/v1/demo-trading/enable');
        expect(postMock).toHaveBeenNthCalledWith(2, '/api/v1/demo-trading/disable');
        expect(postMock).toHaveBeenNthCalledWith(3, '/api/v1/demo-trading/run-once');
    });

    it('calls reset endpoint with confirm=true', async () => {
        postMock.mockResolvedValue({ data: { message: 'reset', running: false } });

        await demoApi.reset();

        expect(postMock).toHaveBeenCalledWith(
            '/api/v1/demo-trading/reset',
            undefined,
            { params: { confirm: true } },
        );
    });

    it('calls trades endpoints with parameters', async () => {
        getMock.mockResolvedValue({ data: { trades: [], total: 0, limit: 20, offset: 0 } });

        await demoApi.getTrades(20, 40);
        await demoApi.getTrade('trade-uuid');
        await demoApi.getOpenTrades();

        expect(getMock).toHaveBeenNthCalledWith(1, '/api/v1/demo-trading/trades', { params: { limit: 20, offset: 40 } });
        expect(getMock).toHaveBeenNthCalledWith(2, '/api/v1/demo-trading/trades/trade-uuid');
        expect(getMock).toHaveBeenNthCalledWith(3, '/api/v1/demo-trading/open-trades');
    });

    it('calls analytics endpoint with lookback', async () => {
        getMock.mockResolvedValue({ data: { lookback: 10 } });

        await demoApi.getAnalytics(10);

        expect(getMock).toHaveBeenCalledWith('/api/v1/demo-trading/analytics/summary', { params: { lookback: 10 } });
    });

    it('calls ai suggestion endpoints', async () => {
        getMock.mockResolvedValue({ data: { batch: null, items: [] } });
        postMock.mockResolvedValue({ data: { message: 'ok', running: false } });

        await demoApi.getLatestSuggestions();
        await demoApi.acceptBatch('batch-uuid');
        await demoApi.rejectBatch('batch-uuid');

        expect(getMock).toHaveBeenCalledWith('/api/v1/demo-trading/ai/suggestions/latest');
        expect(postMock).toHaveBeenNthCalledWith(1, '/api/v1/demo-trading/ai/suggestions/batch-uuid/accept');
        expect(postMock).toHaveBeenNthCalledWith(2, '/api/v1/demo-trading/ai/suggestions/batch-uuid/reject');
    });

    it('calls ai model settings endpoints', async () => {
        getMock.mockResolvedValue({ data: { mode: 'DEMO', tasks: [] } });
        postMock.mockResolvedValue({ data: { mode: 'DEMO', tasks: [] } });

        await demoApi.getAiModels();
        await demoApi.updateAiModels({ revertToDefaults: false, tasks: [] });
        await demoApi.testAiModels('SUGGESTION_BATCH');

        expect(getMock).toHaveBeenCalledWith('/api/v1/demo-trading/ai/models');
        expect(postMock).toHaveBeenNthCalledWith(1, '/api/v1/demo-trading/ai/models', { revertToDefaults: false, tasks: [] });
        expect(postMock).toHaveBeenNthCalledWith(2, '/api/v1/demo-trading/ai/models/test', { taskType: 'SUGGESTION_BATCH' });
    });
});
