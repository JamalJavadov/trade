import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BinanceFillGuide } from './BinanceFillGuide';
import type { RecommendationDTO, RecommendationPlaceabilityDTO } from '../api/client';

vi.mock('../api/client', () => ({
    getRecommendationPlaceability: vi.fn(),
    getSettings: vi.fn(),
    previewRisk: vi.fn(),
    runScanOnce: vi.fn(),
}));

import {
    getRecommendationPlaceability,
    getSettings,
    previewRisk,
} from '../api/client';

const baseRecommendation: RecommendationDTO = {
    id: 'rec-1',
    scanRunId: 'scan-1',
    symbol: 'XRPUSDT',
    side: 'SELL',
    rationaleText: 'test',
    confidenceScore: 2.5,
    createdAt: '2026-02-28T00:00:00Z',
    status: 'NEW',
    leverageRecommendation: 5,
    positionMode: 'ONE_WAY',
    marginMode: 'ISOLATED',
    entryOrder: {
        symbol: 'XRPUSDT',
        side: 'SELL',
        type: 'MARKET',
        quantity: 1000,
    },
    slOrder: {
        symbol: 'XRPUSDT',
        side: 'BUY',
        type: 'STOP_MARKET',
        stopPrice: 0.3320,
        workingType: 'MARK_PRICE',
    },
    tpOrder: {
        symbol: 'XRPUSDT',
        side: 'BUY',
        type: 'TAKE_PROFIT_MARKET',
        stopPrice: 0.3260,
        workingType: 'MARK_PRICE',
    },
};

const invalidPlaceability: RecommendationPlaceabilityDTO = {
    recommendationId: 'rec-1',
    symbol: 'XRPUSDT',
    side: 'SHORT',
    markPrice: 0.3329,
    tickSize: 0.0001,
    reasonCode: 'RR_BELOW_2',
    reasonText: 'Live RR to TP1 is below the minimum required ratio.',
    rules: {
        inequalityRule: 'SL > MARK > TP',
        minTickGap: 1,
    },
    checks: {
        tpOk: true,
        slOk: true,
        rrOk: false,
    },
    computed: {
        entryRef: 'LIVE_MARK',
        rrToTp1: 1.1,
        tp1: 0.3260,
        sl: 0.3320,
        suggestedSlAdjusted: 0.3330,
    },
    tpRaw: 0.3260,
    slRaw: 0.3320,
    tpDisplay: 0.3260,
    slDisplay: 0.3330,
    ruleText: 'SL > MARK > TP',
    requiredInequality: 'SHORT requires SL >= MARK + 1 tick and TP <= MARK - 1 tick.',
    liveRrToTp1: 1.1,
    minRrRequired: 2.0,
    placeable: false,
    manualPlacementAllowed: false,
    violations: ['Live RR to TP1 (1.1) is below minimum required (2.0).'],
    adjustments: ['SL moved to MARK + 1 tick boundary after rounding for SHORT.'],
    checkedAt: '2026-02-28T00:00:00Z',
};

const validPlaceability: RecommendationPlaceabilityDTO = {
    recommendationId: 'rec-1',
    symbol: 'XRPUSDT',
    side: 'SHORT',
    markPrice: 0.3329,
    tickSize: 0.0001,
    reasonCode: 'OK',
    reasonText: 'Trade is placeable against LIVE MARK.',
    rules: {
        inequalityRule: 'SL > MARK > TP',
        minTickGap: 1,
    },
    checks: {
        tpOk: true,
        slOk: true,
        rrOk: true,
    },
    computed: {
        entryRef: 'LIVE_MARK',
        rrToTp1: 6.3,
        tp1: 0.3260,
        sl: 0.3340,
    },
    tpRaw: 0.3260,
    slRaw: 0.3340,
    tpDisplay: 0.3260,
    slDisplay: 0.3340,
    ruleText: 'SL > MARK > TP',
    requiredInequality: 'SHORT requires SL >= MARK + 1 tick and TP <= MARK - 1 tick.',
    liveRrToTp1: 6.3,
    minRrRequired: 2.0,
    placeable: true,
    manualPlacementAllowed: true,
    violations: [],
    adjustments: [],
    checkedAt: '2026-02-28T00:00:00Z',
};

describe('BinanceFillGuide hard block behavior', () => {
    afterEach(() => {
        cleanup();
    });

    beforeEach(() => {
        vi.clearAllMocks();
        vi.mocked(getSettings).mockResolvedValue({
            safeMode: false,
            schedulerEnabled: true,
            scanIntervalMinutes: 20,
            budgetUsdt: 100,
            maxBudgetPct: 5,
            equityOverrideUsdt: 1000,
            maxEquityPct: 1,
        });
        vi.mocked(previewRisk).mockResolvedValue({
            riskUsdtFromEquity: 10,
            riskUsdtFromBudget: 5,
            effectiveRiskUsdt: 5,
            notes: [],
        });
    });

    it('locks copy and exposes only refresh/rescan actions when preflight is invalid', async () => {
        vi.mocked(getRecommendationPlaceability).mockResolvedValue(invalidPlaceability);

        render(
            <MemoryRouter>
                <BinanceFillGuide rec={baseRecommendation} />
            </MemoryRouter>
        );

        await waitFor(() => {
            expect(screen.getByText(/Manual placement is LOCKED/i)).toBeInTheDocument();
        });

        expect(screen.getByRole('button', { name: /^Refresh Mark$/i })).toBeEnabled();
        expect(screen.getByRole('button', { name: /^Rescan now$/i })).toBeEnabled();
        expect(screen.queryByTitle('Copy')).not.toBeInTheDocument();
    });

    it('keeps fail-closed lock when live MARK preflight fails', async () => {
        vi.mocked(getRecommendationPlaceability).mockRejectedValue(new Error('network'));

        render(
            <MemoryRouter>
                <BinanceFillGuide rec={baseRecommendation} />
            </MemoryRouter>
        );

        await waitFor(() => {
            expect(screen.getAllByText(/Manual placement is LOCKED/i).length).toBeGreaterThan(0);
        });
        expect(screen.getByText(/Live MARK preflight could not be fetched/i)).toBeInTheDocument();
        expect(screen.queryByTitle('Copy')).not.toBeInTheDocument();
    });

    it('enables fill and copy when preflight is valid', async () => {
        vi.mocked(getRecommendationPlaceability).mockResolvedValue(validPlaceability);

        render(
            <MemoryRouter>
                <BinanceFillGuide rec={baseRecommendation} />
            </MemoryRouter>
        );

        await waitFor(() => {
            expect(screen.getByText(/Preflight VALID/i)).toBeInTheDocument();
        });

        const copyButtons = screen.getAllByTitle('Copy');
        expect(copyButtons.length).toBeGreaterThan(0);
        copyButtons.forEach((button) => {
            expect(button).toBeEnabled();
        });
    });
});
