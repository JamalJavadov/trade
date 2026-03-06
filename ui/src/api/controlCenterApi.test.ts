import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';

const { getMock, postMock } = vi.hoisted(() => ({
    getMock: vi.fn(),
    postMock: vi.fn(),
}));

const { getSettingsMock, updateSettingsMock } = vi.hoisted(() => ({
    getSettingsMock: vi.fn(),
    updateSettingsMock: vi.fn(),
}));

const { getPermissionsMock, updatePermissionsMock } = vi.hoisted(() => ({
    getPermissionsMock: vi.fn(),
    updatePermissionsMock: vi.fn(),
}));

vi.mock('./axiosSetup', () => ({
    apiClient: {
        get: getMock,
        post: postMock,
    },
}));

vi.mock('./client', () => ({
    getSettings: getSettingsMock,
    updateSettings: updateSettingsMock,
}));

vi.mock('./operatorApi', () => ({
    getPermissions: getPermissionsMock,
    updatePermissions: updatePermissionsMock,
}));

import {
    getControlCenterState,
    getControlCenterStateWithFallback,
    mapSettingsPatchToLegacyPayload,
    updateSettingsLegacy,
} from './controlCenterApi';

describe('controlCenterApi', () => {
    beforeEach(() => {
        getMock.mockReset();
        postMock.mockReset();
        getSettingsMock.mockReset();
        updateSettingsMock.mockReset();
        getPermissionsMock.mockReset();
        updatePermissionsMock.mockReset();
    });

    it('calls control center GET endpoint', async () => {
        getMock.mockResolvedValue({
            data: {
                permissions: [],
                settings: {
                    scan: { intervalMinutes: 10, autoscanEnabled: true, safeMode: false },
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
            },
        });

        await getControlCenterState();

        expect(getMock).toHaveBeenCalledWith('/api/v1/control-center/state');
    });

    it('falls back to legacy endpoints when control center endpoint is unavailable', async () => {
        const unavailable = new AxiosError('Not Found', 'ERR_BAD_REQUEST');
        unavailable.response = {
            data: {},
            status: 404,
            statusText: 'Not Found',
            headers: {},
            config: { headers: {} } as any,
        };
        getMock.mockRejectedValue(unavailable);

        getPermissionsMock.mockResolvedValue([
            {
                key: 'scan.run_once',
                title: 'Run Scan Once',
                description: 'desc',
                group: 'SCAN',
                dangerLevel: 'LOW',
                enabled: true,
                updatedAt: '2026-03-01T00:00:00Z',
                updatedBy: 'operator-1',
            },
        ]);
        getSettingsMock.mockResolvedValue({
            safeMode: false,
            schedulerEnabled: true,
            scanIntervalMinutes: 20,
            budgetUsdt: 10,
            maxBudgetPct: 5,
            equityOverrideUsdt: null,
            maxEquityPct: 1,
        });

        const result = await getControlCenterStateWithFallback();

        expect(result.sourceMode).toBe('legacy-fallback');
        expect(result.capabilities.supportsAiRouting).toBe(false);
        expect(result.state.permissions).toHaveLength(1);
        expect(result.state.settings.scan.autoscanEnabled).toBe(true);
        expect(result.state.settings.budget.usdt).toBe(10);
    });

    it('maps settings patch to legacy payload', () => {
        const payload = mapSettingsPatchToLegacyPayload({
            scan: {
                autoscanEnabled: false,
                safeMode: true,
                intervalMinutes: 12,
            },
            budget: {
                usdt: 200,
            },
            risk: {
                maxBudgetPct: 1.5,
                equityOverrideUsdt: null,
                maxEquityPct: 0.7,
            },
            alerts: {
                enabled: true,
            },
        });

        expect(payload).toEqual({
            schedulerEnabled: false,
            safeMode: true,
            scanIntervalMinutes: 12,
            budgetUsdt: 200,
            maxBudgetPct: 1.5,
            equityOverrideUsdt: null,
            maxEquityPct: 0.7,
        });
    });

    it('updateSettingsLegacy reads current settings when patch has no legacy fields', async () => {
        getSettingsMock.mockResolvedValue({
            safeMode: false,
            schedulerEnabled: true,
            scanIntervalMinutes: 20,
            budgetUsdt: 10,
            maxBudgetPct: 5,
            equityOverrideUsdt: null,
            maxEquityPct: 1,
        });

        await updateSettingsLegacy({
            alerts: {
                enabled: true,
                volume: 20,
            },
        });

        expect(getSettingsMock).toHaveBeenCalledTimes(1);
        expect(updateSettingsMock).not.toHaveBeenCalled();
    });
});
