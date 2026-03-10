import { beforeEach, describe, expect, it, vi } from 'vitest';

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

import {
    createDefaultControlCenterState,
    getControlCenterState,
    patchControlCenterState,
    permissionItemsFromState,
} from './controlCenterApi';

describe('controlCenterApi', () => {
    beforeEach(() => {
        getMock.mockReset();
        postMock.mockReset();
    });

    it('calls the control-center state endpoint and normalizes live execution/runtime fields', async () => {
        getMock.mockResolvedValue({
            data: {
                config: {
                    permissions: {
                        'scan.run_once': true,
                        'live.execution.run': false,
                    },
                    scan: { intervalMinutes: 10, autoscanEnabled: true, safeMode: false },
                    risk: { budgetUsdt: 5, maxBudgetPct: 5, equityOverrideUsdt: null, maxEquityPctLocked: 1 },
                    alerts: { enabled: false, volume: 0.5, durationSeconds: 5 },
                    ai: {
                        enabled: true,
                        allowlist: ['model-primary', 'model-fallback'],
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
                    budgetTargetAutoExecution: {
                        enabled: true,
                        armed: true,
                        readOnly: true,
                        maxConcurrentPositions: 9,
                        defaultBudgetUsdt: 75,
                        defaultTargetProfitUsdt: 12,
                        allowNewSessionStart: false,
                        allowCloseAllOnTarget: false,
                        killSwitch: true,
                        requireBinanceHealthPass: false,
                        requireOperatorConfirmationForStop: false,
                        sessionTimeoutMinutes: 180,
                    },
                    strategyLocks: { executionTf: '15m', biasTf: '1h', fractalPeriod: 5, minRr: 2 },
                },
                serverTime: '2026-03-05T12:00:00Z',
                version: 9,
                permissionCatalog: [],
            },
        });

        const state = await getControlCenterState();

        expect(getMock).toHaveBeenCalledWith('/api/v1/control-center/state');
        expect(state.config.ai.live.routing.scanReview.primaryModel).toBe('model-primary');
        expect(state.config.ai.demo.routing.scanReview.primaryModel).toBe('model-primary');
        expect(state.config.permissions['live.execution.enabled']).toBe(false);
        expect(state.config.liveExecution.readOnly).toBe(true);
        expect(state.config.budgetTargetAutoExecution.enabled).toBe(true);
        expect(state.config.budgetTargetAutoExecution.armed).toBe(true);
        expect(state.config.budgetTargetAutoExecution.readOnly).toBe(true);
        expect(state.config.budgetTargetAutoExecution.defaultBudgetUsdt).toBe(75);
        expect(state.config.budgetTargetAutoExecution.maxConcurrentPositions).toBe(3);
        expect(state.config.budgetTargetAutoExecution.allowCloseAllOnTarget).toBe(true);
        expect(state.config.budgetTargetAutoExecution.killSwitch).toBe(true);
    });

    it('posts state patches and normalizes the response', async () => {
        postMock.mockResolvedValue({
            data: {
                config: {
                    permissions: {},
                    ai: {
                        enabled: true,
                        allowlist: ['model-primary'],
                        live: { routing: {} },
                        demo: { routing: {} },
                    },
                    liveExecution: {
                        readOnly: true,
                    },
                    budgetTargetAutoExecution: {
                        enabled: false,
                        armed: false,
                        readOnly: false,
                        maxConcurrentPositions: 3,
                        defaultBudgetUsdt: 60,
                        defaultTargetProfitUsdt: 8,
                        allowNewSessionStart: true,
                        allowCloseAllOnTarget: true,
                        killSwitch: false,
                        requireBinanceHealthPass: true,
                        requireOperatorConfirmationForStop: true,
                        sessionTimeoutMinutes: 240,
                    },
                },
                serverTime: '2026-03-05T12:00:00Z',
                version: 10,
                permissionCatalog: [],
            },
        });

        const state = await patchControlCenterState({
            patch: { demoTrading: { enabled: true } },
            reason: 'demo-toggle-on',
        });

        expect(postMock).toHaveBeenCalledWith('/api/v1/control-center/state', {
            patch: { demoTrading: { enabled: true } },
            reason: 'demo-toggle-on',
        });
        expect(state.config.demoTrading.enabled).toBe(false);
        expect(state.config.ai.live.routing.scanReview.primaryModel).toBe('openai/gpt-oss-120b:free');
        expect(state.config.permissions['live.execution.enabled']).toBeUndefined();
        expect(state.config.liveExecution.readOnly).toBe(true);
        expect(state.config.budgetTargetAutoExecution.defaultBudgetUsdt).toBe(60);
        expect(state.config.budgetTargetAutoExecution.requireOperatorConfirmationForStop).toBe(true);
    });

    it('normalizes legacy budget-target runtime fields into the new config shape', async () => {
        getMock.mockResolvedValue({
            data: {
                config: {
                    permissions: {},
                    ai: {
                        enabled: true,
                        allowlist: ['model-primary'],
                        live: { routing: {} },
                        demo: { routing: {} },
                    },
                    liveExecution: {
                        readOnly: false,
                    },
                    budgetTargetAutoExecution: {
                        enabled: true,
                        sessionBudgetUsdt: 44,
                        finalTargetNetProfitUsdt: 7,
                        maxActiveTrades: 5,
                    },
                },
                serverTime: '2026-03-05T12:00:00Z',
                version: 10,
                permissionCatalog: [],
            },
        });

        const state = await getControlCenterState();

        expect(state.config.budgetTargetAutoExecution.enabled).toBe(true);
        expect(state.config.budgetTargetAutoExecution.armed).toBe(true);
        expect(state.config.budgetTargetAutoExecution.defaultBudgetUsdt).toBe(44);
        expect(state.config.budgetTargetAutoExecution.defaultTargetProfitUsdt).toBe(7);
        expect(state.config.budgetTargetAutoExecution.maxConcurrentPositions).toBe(3);
        expect(state.config.budgetTargetAutoExecution.allowCloseAllOnTarget).toBe(true);
    });

    it('builds permission items from the catalog when present', () => {
        const state = createDefaultControlCenterState();
        state.permissionCatalog = [
            {
                key: 'scan.run_once',
                title: 'Run Scan Once',
                description: 'Allows a manual scan.',
                group: 'SCAN',
                dangerLevel: 'MED',
            },
        ];
        state.config.permissions['scan.run_once'] = false;

        expect(permissionItemsFromState(state)).toEqual([
            expect.objectContaining({
                key: 'scan.run_once',
                title: 'Run Scan Once',
                enabled: false,
            }),
        ]);
    });
});
