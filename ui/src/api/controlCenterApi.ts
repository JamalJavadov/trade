import { apiClient } from './axiosSetup';
import type { PermissionItem } from './operatorApi';

export interface PermissionCatalogItem {
    key: string;
    title: string;
    description: string;
    group: string;
    dangerLevel: string;
}

export interface ControlCenterTaskRouting {
    primaryModel: string;
    fallbackModels: string[];
}

export interface ControlCenterModeRouting {
    routing: {
        suggestion: ControlCenterTaskRouting;
        explainability: ControlCenterTaskRouting;
        vision: ControlCenterTaskRouting;
        scanReview: ControlCenterTaskRouting;
    };
}

export interface ControlCenterConfig {
    permissions: Record<string, boolean>;
    scan: {
        autoscanEnabled: boolean;
        intervalMinutes: number;
        safeMode: boolean;
    };
    risk: {
        budgetUsdt: number;
        maxBudgetPct: number;
        equityOverrideUsdt: number | null;
        maxEquityPctLocked: number;
    };
    alerts: {
        enabled: boolean;
        volume: number;
        durationSeconds: number;
    };
    ai: {
        enabled: boolean;
        allowlist: string[];
        live: ControlCenterModeRouting;
        demo: ControlCenterModeRouting;
    };
    demoTrading: {
        enabled: boolean;
        intervalMinutes: number;
        maxOpenPositions: number;
        startBalanceUsdt: number;
        riskPct: number;
        leverageDefault: number;
        feeBps: number;
        slippageBps: number;
        timeStopMinutes: number;
    };
    liveExecution: {
        readOnly: boolean;
    };
    strategyLocks: {
        executionTf: string;
        biasTf: string;
        fractalPeriod: number;
        minRr: number;
    };
}

export interface ControlCenterStateResponse {
    config: ControlCenterConfig;
    serverTime: string;
    version: number;
    permissionCatalog: PermissionCatalogItem[];
}

export interface ControlCenterStateRequest {
    patch: Record<string, unknown>;
    reason?: string;
}

const DEFAULT_ROUTING: ControlCenterTaskRouting = {
    primaryModel: 'openai/gpt-oss-120b:free',
    fallbackModels: [],
};

const DEFAULT_CONFIG: ControlCenterConfig = {
    permissions: {},
    scan: {
        autoscanEnabled: false,
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
        volume: 0.9,
        durationSeconds: 10,
    },
    ai: {
        enabled: true,
        allowlist: [],
        live: {
            routing: {
                suggestion: { ...DEFAULT_ROUTING },
                explainability: { ...DEFAULT_ROUTING },
                vision: { ...DEFAULT_ROUTING },
                scanReview: { ...DEFAULT_ROUTING },
            },
        },
        demo: {
            routing: {
                suggestion: { ...DEFAULT_ROUTING },
                explainability: { ...DEFAULT_ROUTING },
                vision: { ...DEFAULT_ROUTING },
                scanReview: { ...DEFAULT_ROUTING },
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
        readOnly: false,
    },
    strategyLocks: {
        executionTf: '15m',
        biasTf: '1h',
        fractalPeriod: 5,
        minRr: 2,
    },
};

const PERMISSION_ALIASES: Record<string, string> = {
    'scan.autoscan.enable': 'scan.autoscan.toggle',
    'ai.models.manage': 'ai.models.update',
    'live.execution.view': 'live.execution.enabled',
    'live.execution.run': 'live.execution.enabled',
    'live.execution.reconcile': 'live.execution.enabled',
};

function normalizePermissions(value: unknown): Record<string, boolean> {
    const raw = isRecord(value) ? value : {};
    const permissions: Record<string, boolean> = {};

    Object.entries(raw).forEach(([key, flag]) => {
        if (
            key === 'live.execution.view'
            || key === 'live.execution.run'
            || key === 'live.execution.reconcile'
        ) {
            return;
        }
        permissions[PERMISSION_ALIASES[key] ?? key] = toBool(flag, true);
    });

    const legacyRun = typeof raw['live.execution.run'] === 'boolean' ? raw['live.execution.run'] : undefined;
    const canonicalLive = typeof raw['live.execution.enabled'] === 'boolean'
        ? raw['live.execution.enabled']
        : undefined;
    const legacyView = typeof raw['live.execution.view'] === 'boolean' ? raw['live.execution.view'] : undefined;
    const legacyReconcile = typeof raw['live.execution.reconcile'] === 'boolean'
        ? raw['live.execution.reconcile']
        : undefined;
    const liveExecutionEnabled = legacyRun ?? canonicalLive ?? legacyView ?? legacyReconcile;

    if (typeof liveExecutionEnabled === 'boolean') {
        permissions['live.execution.enabled'] = liveExecutionEnabled;
    }

    return permissions;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

function toBool(value: unknown, fallback: boolean): boolean {
    return typeof value === 'boolean' ? value : fallback;
}

function toNumber(value: unknown, fallback: number): number {
    if (typeof value === 'number' && Number.isFinite(value)) {
        return value;
    }
    if (typeof value === 'string' && value.trim() !== '') {
        const parsed = Number(value);
        if (Number.isFinite(parsed)) {
            return parsed;
        }
    }
    return fallback;
}

function toStringValue(value: unknown, fallback: string): string {
    return typeof value === 'string' && value.trim() !== '' ? value : fallback;
}

function toStringArray(value: unknown): string[] {
    if (!Array.isArray(value)) {
        return [];
    }
    return value
        .filter((item): item is string => typeof item === 'string')
        .map((item) => item.trim())
        .filter((item) => item.length > 0);
}

function normalizeRouting(value: unknown): ControlCenterTaskRouting {
    const record = isRecord(value) ? value : {};
    return {
        primaryModel: toStringValue(record.primaryModel, DEFAULT_ROUTING.primaryModel),
        fallbackModels: toStringArray(record.fallbackModels),
    };
}

function normalizeModeRouting(value: unknown): ControlCenterModeRouting {
    const routing = isRecord(value) && isRecord(value.routing) ? value.routing : {};
    return {
        routing: {
            suggestion: normalizeRouting(routing.suggestion),
            explainability: normalizeRouting(routing.explainability),
            vision: normalizeRouting(routing.vision),
            scanReview: normalizeRouting(routing.scanReview),
        },
    };
}

export function normalizeControlCenterConfig(value: unknown): ControlCenterConfig {
    const config = isRecord(value) ? value : {};
    const scan = isRecord(config.scan) ? config.scan : {};
    const risk = isRecord(config.risk) ? config.risk : {};
    const alerts = isRecord(config.alerts) ? config.alerts : {};
    const ai = isRecord(config.ai) ? config.ai : {};
    const demoTrading = isRecord(config.demoTrading) ? config.demoTrading : {};
    const liveExecution = isRecord(config.liveExecution) ? config.liveExecution : {};
    const strategyLocks = isRecord(config.strategyLocks) ? config.strategyLocks : {};
    const permissions = normalizePermissions(config.permissions);

    return {
        permissions,
        scan: {
            autoscanEnabled: toBool(scan.autoscanEnabled, DEFAULT_CONFIG.scan.autoscanEnabled),
            intervalMinutes: toNumber(scan.intervalMinutes, DEFAULT_CONFIG.scan.intervalMinutes),
            safeMode: toBool(scan.safeMode, DEFAULT_CONFIG.scan.safeMode),
        },
        risk: {
            budgetUsdt: toNumber(risk.budgetUsdt, DEFAULT_CONFIG.risk.budgetUsdt),
            maxBudgetPct: toNumber(risk.maxBudgetPct, DEFAULT_CONFIG.risk.maxBudgetPct),
            equityOverrideUsdt: risk.equityOverrideUsdt == null ? null : toNumber(risk.equityOverrideUsdt, DEFAULT_CONFIG.risk.budgetUsdt),
            maxEquityPctLocked: toNumber(risk.maxEquityPctLocked, DEFAULT_CONFIG.risk.maxEquityPctLocked),
        },
        alerts: {
            enabled: toBool(alerts.enabled, DEFAULT_CONFIG.alerts.enabled),
            volume: toNumber(alerts.volume, DEFAULT_CONFIG.alerts.volume),
            durationSeconds: toNumber(alerts.durationSeconds, DEFAULT_CONFIG.alerts.durationSeconds),
        },
        ai: {
            enabled: toBool(ai.enabled, DEFAULT_CONFIG.ai.enabled),
            allowlist: toStringArray(ai.allowlist),
            live: normalizeModeRouting(ai.live),
            demo: normalizeModeRouting(ai.demo),
        },
        demoTrading: {
            enabled: toBool(demoTrading.enabled, DEFAULT_CONFIG.demoTrading.enabled),
            intervalMinutes: toNumber(demoTrading.intervalMinutes, DEFAULT_CONFIG.demoTrading.intervalMinutes),
            maxOpenPositions: toNumber(demoTrading.maxOpenPositions, DEFAULT_CONFIG.demoTrading.maxOpenPositions),
            startBalanceUsdt: toNumber(demoTrading.startBalanceUsdt, DEFAULT_CONFIG.demoTrading.startBalanceUsdt),
            riskPct: toNumber(demoTrading.riskPct, DEFAULT_CONFIG.demoTrading.riskPct),
            leverageDefault: toNumber(demoTrading.leverageDefault, DEFAULT_CONFIG.demoTrading.leverageDefault),
            feeBps: toNumber(demoTrading.feeBps, DEFAULT_CONFIG.demoTrading.feeBps),
            slippageBps: toNumber(demoTrading.slippageBps, DEFAULT_CONFIG.demoTrading.slippageBps),
            timeStopMinutes: toNumber(demoTrading.timeStopMinutes, DEFAULT_CONFIG.demoTrading.timeStopMinutes),
        },
        liveExecution: {
            readOnly: toBool(liveExecution.readOnly, DEFAULT_CONFIG.liveExecution.readOnly),
        },
        strategyLocks: {
            executionTf: toStringValue(strategyLocks.executionTf, DEFAULT_CONFIG.strategyLocks.executionTf),
            biasTf: toStringValue(strategyLocks.biasTf, DEFAULT_CONFIG.strategyLocks.biasTf),
            fractalPeriod: toNumber(strategyLocks.fractalPeriod, DEFAULT_CONFIG.strategyLocks.fractalPeriod),
            minRr: toNumber(strategyLocks.minRr, DEFAULT_CONFIG.strategyLocks.minRr),
        },
    };
}

function normalizePermissionCatalog(value: unknown): PermissionCatalogItem[] {
    if (!Array.isArray(value)) {
        return [];
    }
    return value
        .filter((item): item is Record<string, unknown> => isRecord(item) && typeof item.key === 'string')
        .map((item) => ({
            key: String(item.key),
            title: toStringValue(item.title, String(item.key)),
            description: toStringValue(item.description, ''),
            group: toStringValue(item.group, 'OTHER'),
            dangerLevel: toStringValue(item.dangerLevel, 'LOW'),
        }));
}

function normalizeState(value: unknown): ControlCenterStateResponse {
    const raw = isRecord(value) ? value : {};
    return {
        config: normalizeControlCenterConfig(raw.config),
        serverTime: toStringValue(raw.serverTime, new Date().toISOString()),
        version: toNumber(raw.version, 1),
        permissionCatalog: normalizePermissionCatalog(raw.permissionCatalog),
    };
}

export function createDefaultControlCenterState(): ControlCenterStateResponse {
    return normalizeState({
        config: DEFAULT_CONFIG,
        serverTime: new Date().toISOString(),
        version: 1,
        permissionCatalog: [],
    });
}

export function permissionItemsFromState(state: ControlCenterStateResponse | null): PermissionItem[] {
    if (!state) {
        return [];
    }

    const fromCatalog = state.permissionCatalog.map((item) => ({
        key: item.key,
        title: item.title,
        description: item.description,
        group: item.group,
        dangerLevel: item.dangerLevel,
        enabled: state.config.permissions[item.key] ?? true,
        updatedAt: state.serverTime,
        updatedBy: 'control-center',
    }));

    if (fromCatalog.length > 0) {
        return fromCatalog;
    }

    return Object.entries(state.config.permissions).map(([key, enabled]) => ({
        key,
        title: key,
        description: '',
        group: 'OTHER',
        dangerLevel: 'LOW',
        enabled,
        updatedAt: state.serverTime,
        updatedBy: 'control-center',
    }));
}

export async function getControlCenterState(): Promise<ControlCenterStateResponse> {
    const response = await apiClient.get<ControlCenterStateResponse>('/api/v1/control-center/state');
    return normalizeState(response.data);
}

export async function patchControlCenterState(payload: ControlCenterStateRequest): Promise<ControlCenterStateResponse> {
    const response = await apiClient.post<ControlCenterStateResponse>('/api/v1/control-center/state', payload);
    return normalizeState(response.data);
}
