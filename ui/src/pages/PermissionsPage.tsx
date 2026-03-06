import { useCallback, useEffect, useMemo, useState } from 'react';
import { Banner } from '../components/Banner';
import { useControlCenter, usePermissions } from '../hooks/usePermissions';
import { useToastStore } from '../store/toastStore';
import { parseApiError } from '../utils/apiError';
import { getErrorExplanation } from '../utils/errorMap';
import type { ControlCenterConfig, ControlCenterTaskRouting } from '../api/controlCenterApi';
import { getAutoScanState, type AutoScanStateResponse } from '../api/autoscanApi';
import { runScanOnce } from '../api/client';

interface UiError {
    errorCode: string;
    message: string;
    traceId: string | null;
    fix: string;
}

type SavingSection = 'permissions' | 'scan' | 'risk' | 'alerts' | 'ai' | 'demo' | 'reset' | null;

const RESET_DEFAULTS_PATCH: Record<string, unknown> = {
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
    strategyLocks: {
        executionTf: '15m',
        biasTf: '1h',
        fractalPeriod: 5,
        minRr: 2,
    },
};

function toFixedNum(value: number, digits = 2): string {
    return Number.isFinite(value) ? value.toFixed(digits) : '0';
}

function formatInstant(value: string | null | undefined): string {
    if (!value) {
        return 'n/a';
    }
    return new Date(value).toLocaleString();
}

function FallbackEditor({
    value,
    onChange,
    allowlist,
}: {
    value: string[];
    onChange: (next: string[]) => void;
    allowlist: string[];
}) {
    const available = allowlist.filter((model) => !value.includes(model));

    return (
        <div className="space-y-2">
            <div className="flex flex-wrap gap-2">
                {value.map((model, index) => (
                    <div key={`${model}-${index}`} className="flex items-center gap-1 rounded border border-slate-600 bg-slate-900 px-2 py-1 text-xs text-slate-200">
                        <span className="font-mono">{model}</span>
                        <button
                            type="button"
                            className="rounded bg-slate-700 px-1 text-[10px] hover:bg-slate-600"
                            onClick={() => {
                                if (index === 0) {
                                    return;
                                }
                                const next = [...value];
                                [next[index - 1], next[index]] = [next[index], next[index - 1]];
                                onChange(next);
                            }}
                        >
                            ↑
                        </button>
                        <button
                            type="button"
                            className="rounded bg-slate-700 px-1 text-[10px] hover:bg-slate-600"
                            onClick={() => {
                                if (index === value.length - 1) {
                                    return;
                                }
                                const next = [...value];
                                [next[index + 1], next[index]] = [next[index], next[index + 1]];
                                onChange(next);
                            }}
                        >
                            ↓
                        </button>
                        <button
                            type="button"
                            className="rounded bg-rose-800 px-1 text-[10px] hover:bg-rose-700"
                            onClick={() => onChange(value.filter((item) => item !== model))}
                        >
                            ✕
                        </button>
                    </div>
                ))}
            </div>

            <div className="flex gap-2">
                <select
                    className="w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-xs text-slate-100"
                    defaultValue=""
                    onChange={(event) => {
                        const selected = event.target.value;
                        if (!selected) {
                            return;
                        }
                        onChange([...value, selected]);
                        event.currentTarget.value = '';
                    }}
                >
                    <option value="">Add fallback model...</option>
                    {available.map((model) => (
                        <option key={model} value={model}>{model}</option>
                    ))}
                </select>
            </div>
        </div>
    );
}

function RoutingEditor({
    title,
    allowlist,
    value,
    onChange,
}: {
    title: string;
    allowlist: string[];
    value: ControlCenterTaskRouting;
    onChange: (next: ControlCenterTaskRouting) => void;
}) {
    return (
        <div className="rounded border border-slate-700 bg-slate-900/40 p-3">
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-400">{title}</p>
            <label className="mt-2 block text-xs text-slate-400">Primary model</label>
            <select
                className="mt-1 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-xs text-slate-100"
                value={value.primaryModel}
                onChange={(event) => onChange({ ...value, primaryModel: event.target.value })}
            >
                {allowlist.map((model) => (
                    <option key={model} value={model}>{model}</option>
                ))}
            </select>
            <label className="mt-3 block text-xs text-slate-400">Fallback models (ordered)</label>
            <div className="mt-1">
                <FallbackEditor
                    value={value.fallbackModels}
                    onChange={(next) => onChange({ ...value, fallbackModels: next })}
                    allowlist={allowlist.filter((model) => model !== value.primaryModel)}
                />
            </div>
        </div>
    );
}

function SectionCard({
    title,
    description,
    children,
    onSave,
    saving,
    disabled,
    saveLabel,
}: {
    title: string;
    description: string;
    children: React.ReactNode;
    onSave: () => void;
    saving: boolean;
    disabled?: boolean;
    saveLabel?: string;
}) {
    return (
        <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
            <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                    <h2 className="text-lg font-semibold text-white">{title}</h2>
                    <p className="text-xs text-slate-400">{description}</p>
                </div>
                <button
                    type="button"
                    onClick={onSave}
                    disabled={saving || disabled}
                    className="rounded border border-emerald-700/50 bg-emerald-900/30 px-3 py-1.5 text-xs font-semibold text-emerald-200 hover:bg-emerald-900/45 disabled:opacity-50"
                >
                    {saving ? 'Saving...' : (saveLabel ?? 'Save changes')}
                </button>
            </div>
            <div className="mt-4">{children}</div>
        </section>
    );
}

export function PermissionsPage() {
    const {
        permissions,
        loading,
        refresh,
        can,
    } = usePermissions();
    const {
        state,
        loadError,
        patchConfig,
    } = useControlCenter();
    const pushToast = useToastStore((store) => store.pushToast);

    const [savingSection, setSavingSection] = useState<SavingSection>(null);
    const [uiError, setUiError] = useState<UiError | null>(null);

    const [permissionsDraft, setPermissionsDraft] = useState<Record<string, boolean>>({});
    const [scanDraft, setScanDraft] = useState<ControlCenterConfig['scan'] | null>(null);
    const [riskDraft, setRiskDraft] = useState<ControlCenterConfig['risk'] | null>(null);
    const [alertsDraft, setAlertsDraft] = useState<ControlCenterConfig['alerts'] | null>(null);
    const [aiDraft, setAiDraft] = useState<ControlCenterConfig['ai'] | null>(null);
    const [demoDraft, setDemoDraft] = useState<ControlCenterConfig['demoTrading'] | null>(null);
    const [autoScanState, setAutoScanState] = useState<AutoScanStateResponse | null>(null);
    const [autoScanLoading, setAutoScanLoading] = useState(true);
    const [autoScanError, setAutoScanError] = useState<UiError | null>(null);
    const [runNowPending, setRunNowPending] = useState(false);

    useEffect(() => {
        if (!state) {
            return;
        }
        setPermissionsDraft(state.config.permissions);
        setScanDraft(state.config.scan);
        setRiskDraft(state.config.risk);
        setAlertsDraft(state.config.alerts);
        setAiDraft(state.config.ai);
        setDemoDraft(state.config.demoTrading);
    }, [state]);

    useEffect(() => {
        if (!loadError) {
            return;
        }
        const explain = getErrorExplanation(loadError.errorCode);
        setUiError({
            errorCode: loadError.errorCode,
            message: loadError.message,
            traceId: loadError.traceId,
            fix: explain.fix,
        });
    }, [loadError]);

    const loadAutoScanRuntime = useCallback(async () => {
        try {
            const runtime = await getAutoScanState();
            setAutoScanState(runtime);
            setAutoScanError(null);
        } catch (error) {
            const parsed = parseApiError(error);
            const explain = getErrorExplanation(parsed.errorCode);
            setAutoScanError({
                errorCode: parsed.errorCode,
                message: parsed.message,
                traceId: parsed.traceId,
                fix: explain.fix,
            });
        } finally {
            setAutoScanLoading(false);
        }
    }, []);

    useEffect(() => {
        void loadAutoScanRuntime();
        const intervalId = window.setInterval(() => {
            void loadAutoScanRuntime();
        }, 10_000);
        return () => {
            window.clearInterval(intervalId);
        };
    }, [loadAutoScanRuntime]);

    const groupedPermissions = useMemo(() => {
        const byGroup: Record<string, typeof permissions> = {};
        for (const item of permissions) {
            if (!byGroup[item.group]) {
                byGroup[item.group] = [];
            }
            byGroup[item.group].push(item);
        }
        return byGroup;
    }, [permissions]);

    const handleSave = async (
        section: SavingSection,
        patch: Record<string, unknown>,
        successMessage: string,
        reason: string,
    ) => {
        setSavingSection(section);
        setUiError(null);
        try {
            await patchConfig(patch, reason);
            pushToast(successMessage, 'success');
        } catch (error) {
            const parsed = parseApiError(error);
            const explain = getErrorExplanation(parsed.errorCode);
            setUiError({
                errorCode: parsed.errorCode,
                message: parsed.message,
                traceId: parsed.traceId,
                fix: explain.fix,
            });
            pushToast('Save failed', 'error');
        } finally {
            setSavingSection(null);
        }
    };

    const exportConfig = () => {
        if (!state) {
            return;
        }
        const blob = new Blob([JSON.stringify(state.config, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `control-center-config-v${state.version}.json`;
        link.click();
        URL.revokeObjectURL(url);
    };

    const handleRunNow = async () => {
        setRunNowPending(true);
        setAutoScanError(null);
        try {
            const started = await runScanOnce();
            if (started.status === 'ALREADY_RUNNING') {
                pushToast('Scan is already running. Joined existing run.', 'success');
            } else {
                pushToast('Scan run started.', 'success');
            }
            await loadAutoScanRuntime();
        } catch (error) {
            const parsed = parseApiError(error);
            const explain = getErrorExplanation(parsed.errorCode);
            setAutoScanError({
                errorCode: parsed.errorCode,
                message: parsed.message,
                traceId: parsed.traceId,
                fix: explain.fix,
            });
            pushToast('Run now failed', 'error');
        } finally {
            setRunNowPending(false);
        }
    };

    if (loading || !state || !scanDraft || !riskDraft || !alertsDraft || !aiDraft || !demoDraft) {
        return (
            <div className="rounded-xl border border-slate-700 bg-slate-800 p-6 text-sm text-slate-300">
                Loading Control Center state...
            </div>
        );
    }

    const settingsUpdateAllowed = can('settings.update');
    const canRunScanNow = can('scan.run_once');

    return (
        <div className="space-y-6">
            <div className="rounded-xl border border-slate-700 bg-slate-800 p-6 shadow-lg">
                <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                        <h1 className="text-3xl font-bold text-white">Control Center</h1>
                        <p className="mt-1 text-sm text-slate-300">
                            DB-first runtime controls. All permission and behavior changes are persisted to the Control Center state.
                        </p>
                        <p className="mt-2 text-xs text-slate-400">
                            Server time: {new Date(state.serverTime).toLocaleString()} | Version: {state.version}
                        </p>
                    </div>
                    <div className="flex flex-wrap gap-2">
                        <button
                            type="button"
                            onClick={() => void refresh()}
                            className="rounded border border-slate-600 bg-slate-700 px-3 py-1.5 text-xs font-semibold text-slate-200 hover:bg-slate-600"
                        >
                            Refresh
                        </button>
                        <button
                            type="button"
                            onClick={exportConfig}
                            className="rounded border border-blue-700/50 bg-blue-900/30 px-3 py-1.5 text-xs font-semibold text-blue-200 hover:bg-blue-900/45"
                        >
                            Export config JSON
                        </button>
                        <button
                            type="button"
                            disabled={!settingsUpdateAllowed || savingSection === 'reset'}
                            onClick={() => {
                                if (!window.confirm('Reset Control Center state to safe defaults?')) {
                                    return;
                                }
                                void handleSave('reset', RESET_DEFAULTS_PATCH, 'Defaults restored', 'reset-defaults');
                            }}
                            className="rounded border border-rose-700/50 bg-rose-900/30 px-3 py-1.5 text-xs font-semibold text-rose-200 hover:bg-rose-900/45 disabled:opacity-50"
                        >
                            {savingSection === 'reset' ? 'Resetting...' : 'Reset to defaults'}
                        </button>
                    </div>
                </div>
            </div>

            {uiError && (
                <Banner
                    message={`${uiError.errorCode}: ${uiError.message}${uiError.traceId ? ` | traceId=${uiError.traceId}` : ''} | fix: ${uiError.fix}`}
                    onRetry={() => void refresh()}
                />
            )}

            <SectionCard
                title="Permissions"
                description="Toggle access to sensitive actions. Backend enforcement remains authoritative."
                onSave={() => void handleSave('permissions', { permissions: permissionsDraft }, 'Permissions saved', 'permissions-save')}
                saving={savingSection === 'permissions'}
                disabled={!settingsUpdateAllowed}
            >
                {permissions.length === 0 && (
                    <p className="rounded border border-amber-700/50 bg-amber-900/30 px-3 py-2 text-sm text-amber-200">
                        Permissions list is empty. Bootstrap should keep this non-empty; refresh and verify backend migration state.
                    </p>
                )}

                <div className="space-y-4">
                    {Object.entries(groupedPermissions).map(([group, items]) => (
                        <div key={group} className="rounded border border-slate-700 bg-slate-900/40 p-3">
                            <h3 className="text-sm font-semibold text-slate-200">{group}</h3>
                            <div className="mt-2 space-y-2">
                                {items.map((item) => (
                                    <label key={item.key} className="flex items-start justify-between gap-3 rounded border border-slate-700 bg-slate-950/40 p-2">
                                        <div>
                                            <p className="text-sm font-semibold text-slate-100">{item.title}</p>
                                            <p className="text-xs text-slate-400">{item.description || item.key}</p>
                                            <p className="mt-1 text-[11px] font-mono text-slate-500">{item.key}</p>
                                        </div>
                                        <input
                                            type="checkbox"
                                            checked={permissionsDraft[item.key] ?? true}
                                            disabled={!settingsUpdateAllowed}
                                            onChange={(event) => {
                                                const checked = event.target.checked;
                                                setPermissionsDraft((previous) => ({ ...previous, [item.key]: checked }));
                                            }}
                                            className="mt-1 h-4 w-4"
                                        />
                                    </label>
                                ))}
                            </div>
                        </div>
                    ))}
                </div>
            </SectionCard>

            <SectionCard
                title="Scan Settings"
                description="Autoscan scheduler, safety mode, and scan interval (minutes)."
                onSave={() => void handleSave('scan', { scan: scanDraft }, 'Scan settings saved', 'scan-settings-save')}
                saving={savingSection === 'scan'}
                disabled={!settingsUpdateAllowed}
            >
                <div className="mb-4 rounded border border-slate-700 bg-slate-900/40 p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                        <p className="text-sm font-semibold text-slate-100">Autoscan Runtime</p>
                        <div className="flex gap-2">
                            <button
                                type="button"
                                onClick={() => void loadAutoScanRuntime()}
                                className="rounded border border-slate-600 bg-slate-800 px-2 py-1 text-xs text-slate-200 hover:bg-slate-700"
                            >
                                Refresh runtime
                            </button>
                            <button
                                type="button"
                                onClick={() => void handleRunNow()}
                                disabled={runNowPending || !canRunScanNow}
                                className="rounded border border-blue-700/50 bg-blue-900/30 px-2 py-1 text-xs font-semibold text-blue-200 hover:bg-blue-900/45 disabled:opacity-50"
                            >
                                {runNowPending ? 'Starting...' : 'Run now'}
                            </button>
                        </div>
                    </div>

                    {autoScanLoading && (
                        <p className="mt-2 text-xs text-slate-400">Loading autoscan runtime...</p>
                    )}

                    {autoScanError && (
                        <p className="mt-2 text-xs text-rose-300">
                            {autoScanError.errorCode}: {autoScanError.message}
                            {autoScanError.traceId ? ` (traceId=${autoScanError.traceId})` : ''}
                        </p>
                    )}

                    {autoScanState && (
                        <div className="mt-3 space-y-3">
                            <div className="grid grid-cols-1 gap-2 text-xs text-slate-300 md:grid-cols-4">
                                <div className="rounded border border-slate-700 bg-slate-950/40 p-2">
                                    <p className="text-slate-400">Scheduler</p>
                                    <p className="font-semibold">{autoScanState.autoscanEnabled ? 'ENABLED' : 'DISABLED'}</p>
                                </div>
                                <div className="rounded border border-slate-700 bg-slate-950/40 p-2">
                                    <p className="text-slate-400">Safe Mode</p>
                                    <p className="font-semibold">{autoScanState.safeMode ? 'ON' : 'OFF'}</p>
                                </div>
                                <div className="rounded border border-slate-700 bg-slate-950/40 p-2">
                                    <p className="text-slate-400">Next Run</p>
                                    <p className="font-semibold">{formatInstant(autoScanState.nextRunAt)}</p>
                                </div>
                                <div className="rounded border border-slate-700 bg-slate-950/40 p-2">
                                    <p className="text-slate-400">Current Run</p>
                                    <p className="font-semibold">
                                        {autoScanState.runningRun
                                            ? `${autoScanState.runningRun.status} (${autoScanState.runningRun.id.slice(0, 8)})`
                                            : 'IDLE'}
                                    </p>
                                </div>
                            </div>

                            <div className="rounded border border-slate-700 bg-slate-950/40 p-2 text-xs text-slate-300">
                                <p className="text-slate-400">Last Run</p>
                                {autoScanState.lastRun ? (
                                    <p className="font-mono">
                                        {autoScanState.lastRun.status} | {autoScanState.lastRun.triggerType} | {formatInstant(autoScanState.lastRun.finishedAt || autoScanState.lastRun.startedAt)}
                                        {autoScanState.lastRun.errorCode ? ` | ${autoScanState.lastRun.errorCode}` : ''}
                                    </p>
                                ) : (
                                    <p>No run history yet.</p>
                                )}
                            </div>

                            {autoScanState.recentRuns.length > 0 && (
                                <div className="rounded border border-slate-700 bg-slate-950/40 p-2 text-xs text-slate-300">
                                    <p className="mb-1 text-slate-400">Recent Runs</p>
                                    <div className="space-y-1 font-mono">
                                        {autoScanState.recentRuns.slice(0, 4).map((run) => (
                                            <p key={run.id}>
                                                {run.id.slice(0, 8)} | {run.triggerType} | {run.status} | {formatInstant(run.startedAt)}
                                            </p>
                                        ))}
                                    </div>
                                </div>
                            )}
                        </div>
                    )}
                </div>

                <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Autoscan Enabled</span>
                        <div className="mt-2">
                            <input
                                type="checkbox"
                                checked={scanDraft.autoscanEnabled}
                                disabled={!settingsUpdateAllowed}
                                onChange={(event) => setScanDraft({ ...scanDraft, autoscanEnabled: event.target.checked })}
                            />
                        </div>
                    </label>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Safe Mode</span>
                        <div className="mt-2">
                            <input
                                type="checkbox"
                                checked={scanDraft.safeMode}
                                disabled={!settingsUpdateAllowed}
                                onChange={(event) => setScanDraft({ ...scanDraft, safeMode: event.target.checked })}
                            />
                        </div>
                    </label>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Interval Minutes</span>
                        <input
                            type="number"
                            min={1}
                            value={scanDraft.intervalMinutes}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setScanDraft({ ...scanDraft, intervalMinutes: Math.max(1, Number(event.target.value) || 1) })}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>
                </div>
            </SectionCard>

            <SectionCard
                title="Risk & Budget"
                description="Budget and risk controls used by runtime sizing. maxEquityPctLocked is invariant and read-only."
                onSave={() => void handleSave('risk', { risk: riskDraft }, 'Risk settings saved', 'risk-settings-save')}
                saving={savingSection === 'risk'}
                disabled={!settingsUpdateAllowed}
            >
                <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Budget (USDT)</span>
                        <input
                            type="number"
                            min={0.1}
                            step={0.1}
                            value={riskDraft.budgetUsdt}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setRiskDraft({ ...riskDraft, budgetUsdt: Number(event.target.value) || 0 })}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Max Budget %</span>
                        <input
                            type="number"
                            min={0.1}
                            max={20}
                            step={0.1}
                            value={riskDraft.maxBudgetPct}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setRiskDraft({ ...riskDraft, maxBudgetPct: Number(event.target.value) || 0 })}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Equity Override (USDT)</span>
                        <input
                            type="number"
                            min={0}
                            step={0.1}
                            value={riskDraft.equityOverrideUsdt ?? ''}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => {
                                const raw = event.target.value.trim();
                                setRiskDraft({
                                    ...riskDraft,
                                    equityOverrideUsdt: raw === '' ? null : (Number(raw) || null),
                                });
                            }}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>

                    <div className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">maxEquityPctLocked</span>
                        <p className="mt-2 font-semibold text-amber-300">{toFixedNum(riskDraft.maxEquityPctLocked, 2)}%</p>
                    </div>
                </div>

                <div className="mt-3 rounded border border-slate-700 bg-slate-900/40 p-3 text-xs text-slate-300">
                    Effective risk preview: min(budgetRisk, equityRisk)
                    <div className="mt-1 font-mono text-slate-200">
                        budgetRisk = {toFixedNum(riskDraft.budgetUsdt)} * {toFixedNum(riskDraft.maxBudgetPct)}% = {toFixedNum((riskDraft.budgetUsdt * riskDraft.maxBudgetPct) / 100, 4)}
                    </div>
                    <div className="mt-1 font-mono text-slate-200">
                        equityRisk = {(riskDraft.equityOverrideUsdt ?? 0) > 0
                            ? `${toFixedNum(riskDraft.equityOverrideUsdt ?? 0)} * ${toFixedNum(riskDraft.maxEquityPctLocked)}% = ${toFixedNum(((riskDraft.equityOverrideUsdt ?? 0) * riskDraft.maxEquityPctLocked) / 100, 4)}`
                            : 'not set'}
                    </div>
                </div>
            </SectionCard>

            <SectionCard
                title="Alerts"
                description="Client alert behavior. Duration is locked to 10 seconds."
                onSave={() => void handleSave('alerts', { alerts: alertsDraft }, 'Alert settings saved', 'alerts-save')}
                saving={savingSection === 'alerts'}
                disabled={!settingsUpdateAllowed}
            >
                <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Alerts Enabled</span>
                        <div className="mt-2">
                            <input
                                type="checkbox"
                                checked={alertsDraft.enabled}
                                disabled={!settingsUpdateAllowed}
                                onChange={(event) => setAlertsDraft({ ...alertsDraft, enabled: event.target.checked })}
                            />
                        </div>
                    </label>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Volume (0..1)</span>
                        <input
                            type="number"
                            min={0}
                            max={1}
                            step={0.01}
                            value={alertsDraft.volume}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setAlertsDraft({ ...alertsDraft, volume: Number(event.target.value) || 0 })}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>

                    <div className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Duration Seconds</span>
                        <p className="mt-2 font-semibold text-amber-300">{alertsDraft.durationSeconds} (locked)</p>
                    </div>
                </div>
            </SectionCard>

            <SectionCard
                title="Demo Trading (Advanced)"
                description="Advanced demo runtime settings. ON/OFF toggle is managed from the Demo Trading page."
                onSave={() => void handleSave('demo', { demoTrading: demoDraft }, 'Demo settings saved', 'demo-settings-save')}
                saving={savingSection === 'demo'}
                disabled={!settingsUpdateAllowed}
            >
                <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
                    <div className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Enabled (master toggle)</span>
                        <p className="mt-2 font-semibold text-amber-300">{demoDraft.enabled ? 'ON' : 'OFF'}</p>
                    </div>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Interval Minutes</span>
                        <input
                            type="number"
                            min={1}
                            value={demoDraft.intervalMinutes}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setDemoDraft({ ...demoDraft, intervalMinutes: Math.max(1, Number(event.target.value) || 1) })}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Max Open Positions</span>
                        <input
                            type="number"
                            min={1}
                            max={5}
                            value={demoDraft.maxOpenPositions}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setDemoDraft({ ...demoDraft, maxOpenPositions: Math.max(1, Math.min(5, Number(event.target.value) || 1)) })}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Start Balance (USDT)</span>
                        <input
                            type="number"
                            min={1}
                            value={demoDraft.startBalanceUsdt}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setDemoDraft({ ...demoDraft, startBalanceUsdt: Math.max(1, Number(event.target.value) || 1) })}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Risk % (0..1)</span>
                        <input
                            type="number"
                            min={0}
                            max={1}
                            step={0.01}
                            value={demoDraft.riskPct}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setDemoDraft({ ...demoDraft, riskPct: Math.max(0, Math.min(1, Number(event.target.value) || 0)) })}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Leverage Default</span>
                        <input
                            type="number"
                            min={1}
                            value={demoDraft.leverageDefault}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setDemoDraft({ ...demoDraft, leverageDefault: Math.max(1, Number(event.target.value) || 1) })}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Fee (bps)</span>
                        <input
                            type="number"
                            min={0}
                            value={demoDraft.feeBps}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setDemoDraft({ ...demoDraft, feeBps: Math.max(0, Number(event.target.value) || 0) })}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Slippage (bps)</span>
                        <input
                            type="number"
                            min={0}
                            value={demoDraft.slippageBps}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setDemoDraft({ ...demoDraft, slippageBps: Math.max(0, Number(event.target.value) || 0) })}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>

                    <label className="rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <span className="text-xs text-slate-400">Time Stop (min)</span>
                        <input
                            type="number"
                            min={1}
                            value={demoDraft.timeStopMinutes}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setDemoDraft({ ...demoDraft, timeStopMinutes: Math.max(1, Number(event.target.value) || 1) })}
                            className="mt-2 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                        />
                    </label>
                </div>
            </SectionCard>

            <SectionCard
                title="AI Routing"
                description="Live and demo AI routing. Models must be allowlisted; fallback order is runtime order."
                onSave={() => void handleSave('ai', { ai: aiDraft }, 'AI routing saved', 'ai-routing-save')}
                saving={savingSection === 'ai'}
                disabled={!settingsUpdateAllowed}
            >
                <div className="space-y-4">
                    <label className="flex items-center gap-2 rounded border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-200">
                        <input
                            type="checkbox"
                            checked={aiDraft.enabled}
                            disabled={!settingsUpdateAllowed}
                            onChange={(event) => setAiDraft({ ...aiDraft, enabled: event.target.checked })}
                        />
                        AI Enabled
                    </label>

                    <div className="rounded border border-slate-700 bg-slate-900/40 p-3">
                        <p className="text-xs font-semibold uppercase tracking-wider text-slate-400">Allowlist</p>
                        <div className="mt-2 flex flex-wrap gap-2">
                            {aiDraft.allowlist.map((model) => (
                                <span key={model} className="rounded border border-slate-600 bg-slate-950/60 px-2 py-1 font-mono text-[11px] text-slate-200">
                                    {model}
                                </span>
                            ))}
                        </div>
                    </div>

                    <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                        <div className="space-y-3 rounded border border-slate-700 bg-slate-900/40 p-3">
                            <h3 className="text-sm font-semibold text-slate-100">Live Routing</h3>
                            <RoutingEditor
                                title="Suggestion"
                                allowlist={aiDraft.allowlist}
                                value={aiDraft.live.routing.suggestion}
                                onChange={(next) => setAiDraft({
                                    ...aiDraft,
                                    live: {
                                        ...aiDraft.live,
                                        routing: {
                                            ...aiDraft.live.routing,
                                            suggestion: next,
                                        },
                                    },
                                })}
                            />
                            <RoutingEditor
                                title="Explainability"
                                allowlist={aiDraft.allowlist}
                                value={aiDraft.live.routing.explainability}
                                onChange={(next) => setAiDraft({
                                    ...aiDraft,
                                    live: {
                                        ...aiDraft.live,
                                        routing: {
                                            ...aiDraft.live.routing,
                                            explainability: next,
                                        },
                                    },
                                })}
                            />
                            <RoutingEditor
                                title="Vision"
                                allowlist={aiDraft.allowlist}
                                value={aiDraft.live.routing.vision}
                                onChange={(next) => setAiDraft({
                                    ...aiDraft,
                                    live: {
                                        ...aiDraft.live,
                                        routing: {
                                            ...aiDraft.live.routing,
                                            vision: next,
                                        },
                                    },
                                })}
                            />
                        </div>

                        <div className="space-y-3 rounded border border-slate-700 bg-slate-900/40 p-3">
                            <h3 className="text-sm font-semibold text-slate-100">Demo Routing</h3>
                            <RoutingEditor
                                title="Suggestion"
                                allowlist={aiDraft.allowlist}
                                value={aiDraft.demo.routing.suggestion}
                                onChange={(next) => setAiDraft({
                                    ...aiDraft,
                                    demo: {
                                        ...aiDraft.demo,
                                        routing: {
                                            ...aiDraft.demo.routing,
                                            suggestion: next,
                                        },
                                    },
                                })}
                            />
                            <RoutingEditor
                                title="Explainability"
                                allowlist={aiDraft.allowlist}
                                value={aiDraft.demo.routing.explainability}
                                onChange={(next) => setAiDraft({
                                    ...aiDraft,
                                    demo: {
                                        ...aiDraft.demo,
                                        routing: {
                                            ...aiDraft.demo.routing,
                                            explainability: next,
                                        },
                                    },
                                })}
                            />
                            <RoutingEditor
                                title="Vision"
                                allowlist={aiDraft.allowlist}
                                value={aiDraft.demo.routing.vision}
                                onChange={(next) => setAiDraft({
                                    ...aiDraft,
                                    demo: {
                                        ...aiDraft.demo,
                                        routing: {
                                            ...aiDraft.demo.routing,
                                            vision: next,
                                        },
                                    },
                                })}
                            />
                        </div>
                    </div>
                </div>
            </SectionCard>
        </div>
    );
}
