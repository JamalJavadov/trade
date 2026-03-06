import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PermissionItem } from '../api/operatorApi';
import { PermissionsProvider, usePermissions } from './usePermissions';

const {
    getControlCenterStateWithFallbackMock,
    postControlCenterStateMock,
    updatePermissionLegacyMock,
    updateSettingsLegacyMock,
    defaultStateFactory,
} = vi.hoisted(() => ({
    getControlCenterStateWithFallbackMock: vi.fn(),
    postControlCenterStateMock: vi.fn(),
    updatePermissionLegacyMock: vi.fn(),
    updateSettingsLegacyMock: vi.fn(),
    defaultStateFactory: () => ({
        permissions: [] as PermissionItem[],
        settings: {
            scan: { intervalMinutes: 20, autoscanEnabled: true, safeMode: false },
            alerts: { enabled: false, volume: 75 },
            budget: { usdt: 5 },
            risk: { maxBudgetPct: 5, equityOverrideUsdt: null, maxEquityPct: 1 },
            demoTrading: {
                enabled: false,
                intervalMinutes: 15,
                maxOpenPositions: 1,
                riskPct: 0.5,
                feeBps: 4,
                slippageBps: 2,
                timeStopMinutes: 90,
                aiEnabled: true,
                aiEveryNTrades: 10,
                startBalanceUsdt: 1000,
                useMarkPrice: true,
                autostart: false,
            },
            strategyLocks: { executionTf: '15m', biasTf: '1h', fractalPeriod: 5, minRr: 2, maxEquityPctCap: 1 },
        },
        aiRouting: {
            live: { allowlist: [], tasks: {} },
            demo: { allowlist: [], tasks: {} },
        },
    }),
}));

vi.mock('../api/controlCenterApi', () => ({
    createDefaultControlCenterState: defaultStateFactory,
    getControlCenterStateWithFallback: getControlCenterStateWithFallbackMock,
    mergeLegacySettingsIntoState: (_previous: unknown, _updated: unknown) => defaultStateFactory(),
    postControlCenterState: postControlCenterStateMock,
    updatePermissionLegacy: updatePermissionLegacyMock,
    updateSettingsLegacy: updateSettingsLegacyMock,
}));

const permissionRow: PermissionItem = {
    key: 'scan.run_once',
    title: 'Run Scan Once',
    description: 'Run live scan immediately.',
    group: 'SCAN',
    dangerLevel: 'LOW',
    enabled: false,
    updatedAt: '2026-03-01T00:00:00Z',
    updatedBy: 'operator-1',
};

function Probe() {
    const { loading, permissions, can } = usePermissions();

    return (
        <div>
            <p data-testid="loading">{String(loading)}</p>
            <p data-testid="count">{permissions.length}</p>
            <p data-testid="can-scan-run-once">{String(can('scan.run_once'))}</p>
            <p data-testid="can-unknown">{String(can('unknown.permission'))}</p>
        </div>
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

describe('usePermissions', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    afterEach(() => {
        cleanup();
        vi.useRealTimers();
    });

    it('is permissive before first fetch resolves and enforces known key after load', async () => {
        const deferred = createDeferred<{
            state: ReturnType<typeof defaultStateFactory>;
            sourceMode: 'control-center';
            capabilities: {
                controlCenter: boolean;
                legacySettings: boolean;
                legacyPermissions: boolean;
                supportsAlerts: boolean;
                supportsAiRouting: boolean;
                supportsDemoTrading: boolean;
            };
            endpointUsed: string;
        }>();

        getControlCenterStateWithFallbackMock.mockReturnValueOnce(deferred.promise);

        render(
            <PermissionsProvider>
                <Probe />
            </PermissionsProvider>,
        );

        expect(screen.getByTestId('loading')).toHaveTextContent('true');
        expect(screen.getByTestId('can-scan-run-once')).toHaveTextContent('true');

        deferred.resolve({
            state: {
                ...defaultStateFactory(),
                permissions: [permissionRow],
            },
            sourceMode: 'control-center',
            capabilities: {
                controlCenter: true,
                legacySettings: false,
                legacyPermissions: false,
                supportsAlerts: true,
                supportsAiRouting: true,
                supportsDemoTrading: true,
            },
            endpointUsed: '/api/v1/control-center/state',
        });

        await waitFor(() => {
            expect(screen.getByTestId('loading')).toHaveTextContent('false');
        });
        expect(screen.getByTestId('count')).toHaveTextContent('1');
        expect(screen.getByTestId('can-scan-run-once')).toHaveTextContent('false');
        expect(screen.getByTestId('can-unknown')).toHaveTextContent('true');
    });

    it('refreshes permissions every 60 seconds', async () => {
        vi.useFakeTimers();
        getControlCenterStateWithFallbackMock.mockResolvedValue({
            state: {
                ...defaultStateFactory(),
                permissions: [permissionRow],
            },
            sourceMode: 'control-center',
            capabilities: {
                controlCenter: true,
                legacySettings: false,
                legacyPermissions: false,
                supportsAlerts: true,
                supportsAiRouting: true,
                supportsDemoTrading: true,
            },
            endpointUsed: '/api/v1/control-center/state',
        });

        render(
            <PermissionsProvider>
                <Probe />
            </PermissionsProvider>,
        );

        await act(async () => {
            await Promise.resolve();
        });
        expect(getControlCenterStateWithFallbackMock).toHaveBeenCalledTimes(1);

        await act(async () => {
            vi.advanceTimersByTime(60_000);
            await Promise.resolve();
        });

        expect(getControlCenterStateWithFallbackMock).toHaveBeenCalledTimes(2);
    });
});
