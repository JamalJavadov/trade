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
