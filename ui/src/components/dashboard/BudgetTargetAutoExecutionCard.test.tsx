import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { BudgetTargetAutoExecutionCard } from './BudgetTargetAutoExecutionCard';

const useBudgetTargetAutoExecutionMock = vi.hoisted(() => vi.fn());

vi.mock('../../hooks/useBudgetTargetAutoExecution', () => ({
    useBudgetTargetAutoExecution: useBudgetTargetAutoExecutionMock,
}));

describe('BudgetTargetAutoExecutionCard', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        useBudgetTargetAutoExecutionMock.mockReturnValue({
            state: {
                config: {
                    enabled: true,
                    armed: true,
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
            },
            session: {
                id: 'session-1',
                status: 'RUNNING',
                stopReason: null,
                stopReasonMessage: null,
                budgetAmountUsdt: 50,
                targetProfitUsdt: 10,
                completionReason: null,
                sessionBudgetUsdt: 50,
                finalTargetNetProfitUsdt: 10,
                realizedNetPnlUsdt: 3.25,
                unrealizedNetPnlUsdt: 0,
                remainingBankrollUsdt: 46.75,
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
            },
            loading: false,
            reconnecting: false,
            primaryBlockedReason: {
                code: 'ACTIVE_LIMIT_REACHED',
                message: 'Active position limit reached.',
                source: 'event',
            },
        });
    });

    afterEach(() => {
        cleanup();
    });

    it('shows the compact session summary and blocker text', () => {
        render(
            <MemoryRouter>
                <BudgetTargetAutoExecutionCard />
            </MemoryRouter>,
        );

        expect(screen.getByText('Budget Target Auto-Execution')).toBeInTheDocument();
        expect(screen.getByText('RUNNING')).toBeInTheDocument();
        expect(screen.getByText('1 / 3')).toBeInTheDocument();
        expect(screen.getByText('3.25 USDT')).toBeInTheDocument();
        expect(screen.getByText('Active position limit reached.')).toBeInTheDocument();
    });

    it('links operators to the dedicated auto-session page', () => {
        render(
            <MemoryRouter>
                <BudgetTargetAutoExecutionCard />
            </MemoryRouter>,
        );

        expect(screen.getByRole('link', { name: 'Open Auto Session page' })).toHaveAttribute('href', '/auto-session');
    });
});
