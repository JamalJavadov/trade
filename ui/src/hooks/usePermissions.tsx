import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import {
    createDefaultControlCenterState,
    getControlCenterState,
    patchControlCenterState,
    permissionItemsFromState,
    type ControlCenterStateResponse,
} from '../api/controlCenterApi';
import type { PermissionItem } from '../api/operatorApi';
import { parseApiError } from '../utils/apiError';

interface ControlCenterError {
    operation: 'load' | 'save';
    path: string;
    errorCode: string;
    message: string;
    traceId: string | null;
}

interface ControlCenterContextValue {
    state: ControlCenterStateResponse | null;
    loadError: ControlCenterError | null;
    updatePermission: (key: string, enabled: boolean) => Promise<ControlCenterStateResponse>;
    patchSettings: (settingsPatch: Record<string, unknown>) => Promise<ControlCenterStateResponse>;
    patchAiRouting: (aiRoutingPatch: Record<string, unknown>) => Promise<ControlCenterStateResponse>;
    patchConfig: (patch: Record<string, unknown>, reason?: string) => Promise<ControlCenterStateResponse>;
    sourceMode: 'control-center';
    capabilities: {
        controlCenter: true;
        legacySettings: false;
        legacyPermissions: false;
        supportsAlerts: true;
        supportsAiRouting: true;
        supportsDemoTrading: true;
    };
    endpointUsed: '/api/v1/control-center/state';
}

interface PermissionsContextValue extends ControlCenterContextValue {
    permissions: PermissionItem[];
    loading: boolean;
    saving: boolean;
    lastLoadedAt: string | null;
    refresh: () => Promise<void>;
    replacePermissions: (items: PermissionItem[]) => void;
    can: (permissionKey: string) => boolean;
}

const FIXED_CAPABILITIES = {
    controlCenter: true,
    legacySettings: false,
    legacyPermissions: false,
    supportsAlerts: true,
    supportsAiRouting: true,
    supportsDemoTrading: true,
} as const;

const defaultContextValue: PermissionsContextValue = {
    permissions: [],
    loading: true,
    saving: false,
    lastLoadedAt: null,
    refresh: async () => {
        // no-op outside provider
    },
    replacePermissions: () => {
        // no-op outside provider
    },
    can: () => true,
    state: null,
    sourceMode: 'control-center',
    capabilities: FIXED_CAPABILITIES,
    endpointUsed: '/api/v1/control-center/state',
    loadError: null,
    updatePermission: async () => createDefaultControlCenterState(),
    patchSettings: async () => createDefaultControlCenterState(),
    patchAiRouting: async () => createDefaultControlCenterState(),
    patchConfig: async () => createDefaultControlCenterState(),
};

const PermissionsContext = createContext<PermissionsContextValue>(defaultContextValue);

const PERMISSION_REFRESH_INTERVAL_MS = 60_000;
const LEGACY_ALIAS_MAP: Record<string, string> = {
    'scan.autoscan.enable': 'scan.autoscan.toggle',
    'ai.models.manage': 'ai.models.update',
    'live.execution.view': 'live.execution.enabled',
    'live.execution.run': 'live.execution.enabled',
    'live.execution.reconcile': 'live.execution.enabled',
};

function toControlCenterError(error: unknown, operation: ControlCenterError['operation']): ControlCenterError {
    const parsed = parseApiError(error);
    return {
        operation,
        path: '/api/v1/control-center/state',
        errorCode: parsed.errorCode,
        message: parsed.message,
        traceId: parsed.traceId,
    };
}

export function PermissionsProvider({ children }: { children: React.ReactNode }) {
    const [state, setState] = useState<ControlCenterStateResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [lastLoadedAt, setLastLoadedAt] = useState<string | null>(null);
    const [loadError, setLoadError] = useState<ControlCenterError | null>(null);

    const stateRef = useRef<ControlCenterStateResponse | null>(null);
    useEffect(() => {
        stateRef.current = state;
    }, [state]);

    const permissions = useMemo(() => permissionItemsFromState(state), [state]);

    const loadControlCenterState = useCallback(async () => {
        try {
            const loaded = await getControlCenterState();
            setState(loaded);
            setLoadError(null);
            setLastLoadedAt(new Date().toISOString());
        } catch (error) {
            setLoadError(toControlCenterError(error, 'load'));
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        void loadControlCenterState();

        const intervalId = window.setInterval(() => {
            void loadControlCenterState();
        }, PERMISSION_REFRESH_INTERVAL_MS);

        return () => {
            window.clearInterval(intervalId);
        };
    }, [loadControlCenterState]);

    const patchConfig = useCallback(async (patch: Record<string, unknown>, reason?: string) => {
        setSaving(true);
        try {
            const updated = await patchControlCenterState({ patch, reason });
            setState(updated);
            setLoadError(null);
            setLastLoadedAt(new Date().toISOString());
            return updated;
        } catch (error) {
            setLoadError(toControlCenterError(error, 'save'));
            throw error;
        } finally {
            setSaving(false);
        }
    }, []);

    const updatePermission = useCallback(async (key: string, enabled: boolean) => {
        return patchConfig({ permissions: { [key]: enabled } }, `permission:${key}=${enabled}`);
    }, [patchConfig]);

    const patchSettings = useCallback(async (settingsPatch: Record<string, unknown>) => {
        return patchConfig(settingsPatch, 'settings-section-save');
    }, [patchConfig]);

    const patchAiRouting = useCallback(async (aiRoutingPatch: Record<string, unknown>) => {
        return patchConfig({ ai: aiRoutingPatch }, 'ai-routing-save');
    }, [patchConfig]);

    const replacePermissions = useCallback((items: PermissionItem[]) => {
        const nextPermissions = items.reduce<Record<string, boolean>>((acc, item) => {
            acc[item.key] = Boolean(item.enabled);
            return acc;
        }, {});

        setState((previous) => {
            const base = previous ?? createDefaultControlCenterState();
            return {
                ...base,
                config: {
                    ...base.config,
                    permissions: nextPermissions,
                },
            };
        });
    }, []);

    const permissionEnabledByKey = useMemo(() => {
        if (!state) {
            return {} as Record<string, boolean>;
        }
        return state.config.permissions;
    }, [state]);

    const can = useCallback((permissionKey: string) => {
        const canonicalKey = LEGACY_ALIAS_MAP[permissionKey] ?? permissionKey;
        if (loading) {
            return true;
        }
        if (!Object.prototype.hasOwnProperty.call(permissionEnabledByKey, canonicalKey)) {
            return true;
        }
        return Boolean(permissionEnabledByKey[canonicalKey]);
    }, [loading, permissionEnabledByKey]);

    const value = useMemo<PermissionsContextValue>(() => ({
        permissions,
        loading,
        saving,
        lastLoadedAt,
        refresh: loadControlCenterState,
        replacePermissions,
        can,
        state,
        sourceMode: 'control-center',
        capabilities: FIXED_CAPABILITIES,
        endpointUsed: '/api/v1/control-center/state',
        loadError,
        updatePermission,
        patchSettings,
        patchAiRouting,
        patchConfig,
    }), [
        can,
        lastLoadedAt,
        loadControlCenterState,
        loadError,
        loading,
        patchAiRouting,
        patchConfig,
        patchSettings,
        permissions,
        replacePermissions,
        saving,
        state,
        updatePermission,
    ]);

    return (
        <PermissionsContext.Provider value={value}>
            {children}
        </PermissionsContext.Provider>
    );
}

export function usePermissions() {
    return useContext(PermissionsContext);
}

export function useControlCenter(): ControlCenterContextValue {
    const context = useContext(PermissionsContext);
    return {
        state: context.state,
        sourceMode: context.sourceMode,
        capabilities: context.capabilities,
        endpointUsed: context.endpointUsed,
        loadError: context.loadError,
        updatePermission: context.updatePermission,
        patchSettings: context.patchSettings,
        patchAiRouting: context.patchAiRouting,
        patchConfig: context.patchConfig,
    };
}
