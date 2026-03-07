import React from 'react';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PermissionsPage } from './PermissionsPage';
import type { AutoScanStateResponse } from '../api/autoscanApi';
import type { ControlCenterStateResponse } from '../api/controlCenterApi';

const {
    usePermissionsMock,
    useControlCenterMock,
    getAutoScanStateMock,
    runScanOnceMock,
    patchConfigMock,
} = vi.hoisted(() => ({
    usePermissionsMock: vi.fn(),
    useControlCenterMock: vi.fn(),
    getAutoScanStateMock: vi.fn(),
    runScanOnceMock: vi.fn(),
    patchConfigMock: vi.fn(),
}));

vi.mock('../hooks/usePermissions', () => ({
    usePermissions: usePermissionsMock,
    useControlCenter: useControlCenterMock,
}));

vi.mock('../api/autoscanApi', () => ({
    getAutoScanState: getAutoScanStateMock,
}));

vi.mock('../api/client', () => ({
    runScanOnce: runScanOnceMock,
}));

const controlCenterState: ControlCenterStateResponse = {
    config: {
        permissions: {
            'settings.update': true,
            'scan.run_once': true,
            'live.execution.enabled': false,
        },
        scan: {
            autoscanEnabled: true,
            intervalMinutes: 20,
            safeMode: false,
        },
        risk: {
            budgetUsdt: 50,
            maxBudgetPct: 5,
            equityOverrideUsdt: null,
            maxEquityPctLocked: 1,
        },
        alerts: {
            enabled: true,
            volume: 0.8,
            durationSeconds: 10,
        },
        ai: {
            enabled: true,
            allowlist: ['model-primary', 'model-fallback'],
            live: {
                routing: {
                    suggestion: { primaryModel: 'model-primary', fallbackModels: ['model-fallback'] },
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
            startBalanceUsdt: 1000,
            riskPct: 0.5,
            leverageDefault: 5,
            feeBps: 4,
            slippageBps: 2,
            timeStopMinutes: 90,
        },
        liveExecution: {
            readOnly: true,
        },
        strategyLocks: {
            executionTf: '15m',
            biasTf: '1h',
            fractalPeriod: 5,
            minRr: 2,
        },
    },
    serverTime: '2026-03-05T12:00:00Z',
    version: 9,
    permissionCatalog: [
        {
            key: 'settings.update',
            title: 'Update Settings',
            description: 'Allows control-center setting changes.',
            group: 'SETTINGS',
            dangerLevel: 'HIGH',
        },
        {
            key: 'scan.run_once',
            title: 'Run Scan Once',
            description: 'Allows on-demand scan runs.',
            group: 'SCAN',
            dangerLevel: 'MED',
        },
        {
            key: 'live.execution.enabled',
            title: 'Enable Live Execution',
            description: 'Allows manual Binance Futures order execution.',
            group: 'LIVE',
            dangerLevel: 'HIGH',
        },
    ],
};

const runtimeState: AutoScanStateResponse = {
    autoscanEnabled: true,
    safeMode: false,
    intervalMinutes: 20,
    nextRunAt: '2026-03-05T12:20:00Z',
    runningRun: null,
    lastRun: {
        id: '11111111-2222-3333-4444-555555555555',
        status: 'FINISHED',
        triggerType: 'SCHEDULED',
        requestedAt: '2026-03-05T12:00:00Z',
        startedAt: '2026-03-05T12:00:00Z',
        finishedAt: '2026-03-05T12:01:00Z',
        errorCode: null,
        errorMessage: null,
        correlationId: 'trace-finished',
    },
    recentRuns: [
        {
            id: '11111111-2222-3333-4444-555555555555',
            status: 'FINISHED',
            triggerType: 'SCHEDULED',
            requestedAt: '2026-03-05T12:00:00Z',
            startedAt: '2026-03-05T12:00:00Z',
            finishedAt: '2026-03-05T12:01:00Z',
            errorCode: null,
            errorMessage: null,
            correlationId: 'trace-finished',
        },
    ],
    serverTime: '2026-03-05T12:02:00Z',
};

describe('PermissionsPage autoscan runtime', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        getAutoScanStateMock.mockResolvedValue(runtimeState);
        runScanOnceMock.mockResolvedValue({ scanRunId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', status: 'STARTED' });
        patchConfigMock.mockResolvedValue(controlCenterState);

        usePermissionsMock.mockReturnValue({
            permissions: [
                {
                    key: 'settings.update',
                    title: 'Update Settings',
                    description: 'Allows control-center setting changes.',
                    group: 'SETTINGS',
                    dangerLevel: 'HIGH',
                    enabled: true,
                    updatedAt: '2026-03-05T12:00:00Z',
                    updatedBy: 'control-center',
                },
                {
                    key: 'scan.run_once',
                    title: 'Run Scan Once',
                    description: 'Allows on-demand scan runs.',
                    group: 'SCAN',
                    dangerLevel: 'MED',
                    enabled: true,
                    updatedAt: '2026-03-05T12:00:00Z',
                    updatedBy: 'control-center',
                },
                {
                    key: 'live.execution.enabled',
                    title: 'Enable Live Execution',
                    description: 'Allows manual Binance Futures order execution.',
                    group: 'LIVE',
                    dangerLevel: 'HIGH',
                    enabled: false,
                    updatedAt: '2026-03-05T12:00:00Z',
                    updatedBy: 'control-center',
                },
            ],
            loading: false,
            saving: false,
            lastLoadedAt: '2026-03-05T12:00:00Z',
            refresh: vi.fn().mockResolvedValue(undefined),
            replacePermissions: vi.fn(),
            can: vi.fn().mockReturnValue(true),
            state: controlCenterState,
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
            loadError: null,
            updatePermission: vi.fn(),
            patchSettings: vi.fn(),
            patchAiRouting: vi.fn(),
            patchConfig: patchConfigMock,
        });

        useControlCenterMock.mockReturnValue({
            state: controlCenterState,
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
            loadError: null,
            updatePermission: vi.fn(),
            patchSettings: vi.fn(),
            patchAiRouting: vi.fn(),
            patchConfig: patchConfigMock,
        });
    });

    afterEach(() => {
        cleanup();
    });

    it('loads and renders autoscan runtime state on page open', async () => {
        render(<PermissionsPage />);

        await waitFor(() => {
            expect(getAutoScanStateMock).toHaveBeenCalledTimes(1);
        });

        expect(screen.getByText('Autoscan Runtime')).toBeInTheDocument();
        expect(screen.getByText('Scheduler')).toBeInTheDocument();
        expect(screen.getByText('Recent Runs')).toBeInTheDocument();
        expect(screen.getByText(/11111111 \| SCHEDULED \| FINISHED/)).toBeInTheDocument();
    });

    it('starts run-now and refreshes runtime snapshot', async () => {
        const user = userEvent.setup();
        render(<PermissionsPage />);

        await waitFor(() => {
            expect(getAutoScanStateMock).toHaveBeenCalledTimes(1);
        });

        await user.click(screen.getByRole('button', { name: 'Run now' }));

        await waitFor(() => {
            expect(runScanOnceMock).toHaveBeenCalledTimes(1);
        });
        await waitFor(() => {
            expect(getAutoScanStateMock).toHaveBeenCalledTimes(2);
        });
    });

    it('disables run-now without permission and shows structured error on failure', async () => {
        const canMock = vi.fn((permission: string) => permission !== 'scan.run_once');
        usePermissionsMock.mockReturnValue({
            permissions: [
                {
                    key: 'settings.update',
                    title: 'Update Settings',
                    description: 'Allows control-center setting changes.',
                    group: 'SETTINGS',
                    dangerLevel: 'HIGH',
                    enabled: true,
                    updatedAt: '2026-03-05T12:00:00Z',
                    updatedBy: 'control-center',
                },
                {
                    key: 'scan.run_once',
                    title: 'Run Scan Once',
                    description: 'Allows on-demand scan runs.',
                    group: 'SCAN',
                    dangerLevel: 'MED',
                    enabled: false,
                    updatedAt: '2026-03-05T12:00:00Z',
                    updatedBy: 'control-center',
                },
            ],
            loading: false,
            saving: false,
            lastLoadedAt: '2026-03-05T12:00:00Z',
            refresh: vi.fn().mockResolvedValue(undefined),
            replacePermissions: vi.fn(),
            can: canMock,
            state: controlCenterState,
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
            loadError: null,
            updatePermission: vi.fn(),
            patchSettings: vi.fn(),
            patchAiRouting: vi.fn(),
            patchConfig: vi.fn().mockResolvedValue(controlCenterState),
        });
        getAutoScanStateMock.mockRejectedValueOnce(new Error('state unavailable'));

        render(<PermissionsPage />);

        const runNowButton = screen.getByRole('button', { name: 'Run now' });
        expect(runNowButton).toBeDisabled();

        await waitFor(() => {
            expect(screen.getByText(/INTERNAL: state unavailable/)).toBeInTheDocument();
        });
    });

    it('edits the single live execution capability and read-only gate from the runtime section', async () => {
        const user = userEvent.setup();
        render(<PermissionsPage />);

        const runtimeHeader = await screen.findByText('Live Execution Runtime');
        const runtimeSection = runtimeHeader.closest('section');

        const capabilityToggle = screen.getByRole('checkbox', { name: /Enable manual live execution/i });
        const readOnlyToggle = screen.getByRole('checkbox', { name: /READ-ONLY mode/i });
        expect(capabilityToggle).not.toBeChecked();
        expect(readOnlyToggle).toBeChecked();

        await user.click(capabilityToggle);
        await user.click(readOnlyToggle);

        if (!runtimeSection) {
            throw new Error('Live execution runtime section not found');
        }
        await user.click(within(runtimeSection).getByRole('button', { name: 'Save live execution' }));

        await waitFor(() => {
            expect(patchConfigMock).toHaveBeenCalledWith({
                permissions: {
                    'live.execution.enabled': true,
                },
                liveExecution: {
                    readOnly: false,
                },
            }, 'live-execution-runtime-save');
        });
    });
});
