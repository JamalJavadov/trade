import React from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ControlCenterStateResponse } from '../api/controlCenterApi';
import { PermissionsProvider, usePermissions } from './usePermissions';

const {
    getControlCenterStateMock,
    patchControlCenterStateMock,
    defaultStateFactory,
} = vi.hoisted(() => ({
    getControlCenterStateMock: vi.fn(),
    patchControlCenterStateMock: vi.fn(),
    defaultStateFactory: (): ControlCenterStateResponse => ({
        config: {
            permissions: {},
            scan: { intervalMinutes: 20, autoscanEnabled: true, safeMode: false },
            risk: { budgetUsdt: 5, maxBudgetPct: 5, equityOverrideUsdt: null, maxEquityPctLocked: 1 },
            alerts: { enabled: false, volume: 0.75, durationSeconds: 10 },
            ai: {
                enabled: true,
                allowlist: ['model-primary'],
                live: {
                    routing: {
                        suggestion: { primaryModel: 'model-primary', fallbackModels: [] },
                        explainability: { primaryModel: 'model-primary', fallbackModels: [] },
                        vision: { primaryModel: 'model-primary', fallbackModels: [] },
                        scanReview: { primaryModel: 'model-primary', fallbackModels: [] },
                    },
                },
                demo: {
                    routing: {
                        suggestion: { primaryModel: 'model-primary', fallbackModels: [] },
                        explainability: { primaryModel: 'model-primary', fallbackModels: [] },
                        vision: { primaryModel: 'model-primary', fallbackModels: [] },
                        scanReview: { primaryModel: 'model-primary', fallbackModels: [] },
                    },
                },
            },
            demoTrading: {
                enabled: false,
                intervalMinutes: 15,
                maxOpenPositions: 1,
                riskPct: 0.5,
                feeBps: 4,
                slippageBps: 2,
                timeStopMinutes: 90,
                startBalanceUsdt: 1000,
                leverageDefault: 5,
            },
            strategyLocks: { executionTf: '15m', biasTf: '1h', fractalPeriod: 5, minRr: 2 },
        },
        serverTime: '2026-03-01T00:00:00Z',
        version: 1,
        permissionCatalog: [
            {
                key: 'scan.run_once',
                title: 'Run Scan Once',
                description: 'Run live scan immediately.',
                group: 'SCAN',
                dangerLevel: 'LOW',
            },
        ],
    }),
}));

vi.mock('../api/controlCenterApi', () => ({
    createDefaultControlCenterState: defaultStateFactory,
    getControlCenterState: getControlCenterStateMock,
    patchControlCenterState: patchControlCenterStateMock,
    permissionItemsFromState: (state: ControlCenterStateResponse | null) => {
        if (!state) {
            return [];
        }

        return state.permissionCatalog.map((item) => ({
            key: item.key,
            title: item.title,
            description: item.description,
            group: item.group,
            dangerLevel: item.dangerLevel,
            enabled: state.config.permissions[item.key] ?? true,
            updatedAt: state.serverTime,
            updatedBy: 'control-center',
        }));
    },
}));

function Probe() {
    const { loading, permissions, can } = usePermissions();

    return (
        <div>
            <p data-testid="loading">{String(loading)}</p>
            <p data-testid="count">{permissions.length}</p>
            <p data-testid="can-scan-run-once">{String(can('scan.run_once'))}</p>
            <p data-testid="can-live-run-alias">{String(can('live.execution.run'))}</p>
            <p data-testid="can-unknown">{String(can('unknown.permission'))}</p>
        </div>
    );
}

function createDeferred<T>() {
    let resolve: (value: T) => void = () => {};
    const promise = new Promise<T>((res) => {
        resolve = res;
    });
    return { promise, resolve };
}

describe('usePermissions', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    afterEach(() => {
        cleanup();
        vi.useRealTimers();
    });

    it('is permissive before first fetch resolves and enforces known key after load', async () => {
        const deferred = createDeferred<ControlCenterStateResponse>();
        getControlCenterStateMock.mockReturnValueOnce(deferred.promise);

        render(
            <PermissionsProvider>
                <Probe />
            </PermissionsProvider>,
        );

        expect(screen.getByTestId('loading')).toHaveTextContent('true');
        expect(screen.getByTestId('can-scan-run-once')).toHaveTextContent('true');

        deferred.resolve({
            ...defaultStateFactory(),
            config: {
                ...defaultStateFactory().config,
                permissions: {
                    'scan.run_once': false,
                    'live.execution.enabled': false,
                },
            },
        });

        await waitFor(() => {
            expect(screen.getByTestId('loading')).toHaveTextContent('false');
        });
        expect(screen.getByTestId('count')).toHaveTextContent('1');
        expect(screen.getByTestId('can-scan-run-once')).toHaveTextContent('false');
        expect(screen.getByTestId('can-live-run-alias')).toHaveTextContent('false');
        expect(screen.getByTestId('can-unknown')).toHaveTextContent('true');
    });

    it('refreshes permissions every 60 seconds', async () => {
        vi.useFakeTimers();
        getControlCenterStateMock.mockResolvedValue({
            ...defaultStateFactory(),
            config: {
                ...defaultStateFactory().config,
                permissions: {
                    'scan.run_once': false,
                },
            },
        });

        render(
            <PermissionsProvider>
                <Probe />
            </PermissionsProvider>,
        );

        await act(async () => {
            await Promise.resolve();
        });
        expect(getControlCenterStateMock).toHaveBeenCalledTimes(1);

        await act(async () => {
            vi.advanceTimersByTime(60_000);
            await Promise.resolve();
        });

        expect(getControlCenterStateMock).toHaveBeenCalledTimes(2);
    });
});
