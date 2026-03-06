import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ScanPage } from './ScanPage';

const {
    usePermissionsMock,
    useLiveScanMock,
    getLatestScanMock,
    runScanOnceMock,
    parseApiErrorMock,
} = vi.hoisted(() => ({
    usePermissionsMock: vi.fn(),
    useLiveScanMock: vi.fn(),
    getLatestScanMock: vi.fn(),
    runScanOnceMock: vi.fn(),
    parseApiErrorMock: vi.fn(),
}));

vi.mock('../hooks/usePermissions', () => ({
    usePermissions: usePermissionsMock,
}));

vi.mock('../hooks/useLiveScan', () => ({
    useLiveScan: useLiveScanMock,
}));

vi.mock('../api/scan', () => ({
    getLatestScan: getLatestScanMock,
}));

vi.mock('../api/client', () => ({
    runScanOnce: runScanOnceMock,
}));

vi.mock('../utils/apiError', () => ({
    parseApiError: parseApiErrorMock,
}));

function renderPage(initialPath = '/scan') {
    return render(
        <MemoryRouter initialEntries={[initialPath]}>
            <Routes>
                <Route path="/scan" element={<ScanPage />} />
                <Route path="/scan/:scanRunId" element={<ScanPage />} />
            </Routes>
        </MemoryRouter>,
    );
}

describe('ScanPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        usePermissionsMock.mockReturnValue({
            can: vi.fn().mockReturnValue(true),
        });
        useLiveScanMock.mockReturnValue({
            status: 'FAILED',
            summary: {
                id: '11111111-1111-1111-1111-111111111111',
                startedAt: '2026-03-05T18:00:00Z',
                finishedAt: '2026-03-05T18:00:05Z',
                status: 'FAILED',
                intervalMinutes: 20,
                topN: 300,
                bestRecommendationId: null,
                phases: [],
                evaluatedCount: 0,
                validCount: 0,
                noTradeCount: 0,
                notes: 'Upstream response exceeded configured client buffer.',
            },
            finalSummary: null,
            phases: [],
            progress: null,
            evaluations: new Map(),
            finalEvals: [],
            sseConnected: false,
            reconnecting: false,
            polling: false,
            finalCharts: null,
            lastUpdatedAt: undefined,
        });
        getLatestScanMock.mockResolvedValue(null);
        runScanOnceMock.mockResolvedValue({ scanRunId: null, status: null });
        parseApiErrorMock.mockReturnValue({
            errorCode: 'INTERNAL',
            message: 'Unknown error',
            traceId: null,
            fieldErrors: {},
        });
    });

    afterEach(() => {
        cleanup();
    });

    it('shows failed scan reason in header', async () => {
        renderPage('/scan/11111111-1111-1111-1111-111111111111');
        expect(await screen.findByText(/Reason:/)).toHaveTextContent(
            'Reason: Upstream response exceeded configured client buffer.',
        );
    });

    it('shows actionable run-start error with errorCode and traceId', async () => {
        const user = userEvent.setup();
        runScanOnceMock.mockRejectedValueOnce(new Error('Request failed'));
        parseApiErrorMock.mockReturnValueOnce({
            errorCode: 'BINANCE_NETWORK',
            message: 'Binance network/timeout failure.',
            traceId: 'trace-abc123',
            fieldErrors: {},
        });

        renderPage('/scan');

        await user.click(screen.getByRole('button', { name: /Run Scan Now/i }));

        await waitFor(() => {
            expect(screen.getByText(/Failed to start scan: \[BINANCE_NETWORK\]/)).toBeInTheDocument();
            expect(screen.getByText(/trace trace-abc123/)).toBeInTheDocument();
        });
    });
});
