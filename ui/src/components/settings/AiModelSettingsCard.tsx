import { useEffect, useMemo, useState } from 'react';
import type {
    AiModelsResponseDTO,
    AiModelsTestResponseDTO,
    AiModelsUpdateRequestDTO,
    AiTaskType,
} from '../../api/client';
import { parseApiError } from '../../utils/apiError';

interface AiModelSettingsCardProps {
    title: string;
    subtitle: string;
    modeLabel: 'LIVE' | 'DEMO';
    fetchSettings: () => Promise<AiModelsResponseDTO>;
    updateSettings: (payload: AiModelsUpdateRequestDTO) => Promise<AiModelsResponseDTO>;
    testCall: (taskType?: AiTaskType) => Promise<AiModelsTestResponseDTO>;
}

export function AiModelSettingsCard({
    title,
    subtitle,
    modeLabel,
    fetchSettings,
    updateSettings,
    testCall,
}: AiModelSettingsCardProps) {
    const [data, setData] = useState<AiModelsResponseDTO | null>(null);
    const [draftPrimary, setDraftPrimary] = useState('');
    const [draftFallbacks, setDraftFallbacks] = useState<string[]>([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [testing, setTesting] = useState(false);
    const [error, setError] = useState<{ code: string; message: string; traceId: string | null } | null>(null);
    const [testResult, setTestResult] = useState<AiModelsTestResponseDTO | null>(null);

    const task = data?.tasks.find((item) => item.taskType === 'SUGGESTION_BATCH') ?? null;
    const controlsEnabled = data?.controlsEnabled ?? false;
    const allowlist = data?.allowlist ?? [];

    useEffect(() => {
        let mounted = true;
        const load = async () => {
            setLoading(true);
            try {
                const response = await fetchSettings();
                if (!mounted) {
                    return;
                }
                setData(response);
                const suggestionTask = response.tasks.find((item) => item.taskType === 'SUGGESTION_BATCH');
                setDraftPrimary(suggestionTask?.primaryModel ?? '');
                setDraftFallbacks(suggestionTask?.fallbackModels ?? []);
                setError(null);
            } catch (err) {
                if (!mounted) {
                    return;
                }
                const parsed = parseApiError(err);
                setError({ code: parsed.errorCode, message: parsed.message, traceId: parsed.traceId });
            } finally {
                if (mounted) {
                    setLoading(false);
                }
            }
        };

        void load();
        return () => {
            mounted = false;
        };
    }, [fetchSettings]);

    const hasChanges = useMemo(() => {
        if (!task) {
            return false;
        }
        if (task.primaryModel !== draftPrimary) {
            return true;
        }
        if (task.fallbackModels.length !== draftFallbacks.length) {
            return true;
        }
        return task.fallbackModels.some((value, index) => value !== draftFallbacks[index]);
    }, [draftFallbacks, draftPrimary, task]);

    const handleFallbackToggle = (model: string, checked: boolean) => {
        setDraftFallbacks((current) => {
            if (checked) {
                if (current.includes(model)) {
                    return current;
                }
                return [...current, model];
            }
            return current.filter((item) => item !== model);
        });
    };

    const refresh = async () => {
        const response = await fetchSettings();
        setData(response);
        const suggestionTask = response.tasks.find((item) => item.taskType === 'SUGGESTION_BATCH');
        setDraftPrimary(suggestionTask?.primaryModel ?? '');
        setDraftFallbacks(suggestionTask?.fallbackModels ?? []);
    };

    const handleSave = async () => {
        if (!controlsEnabled || !draftPrimary) {
            return;
        }
        setSaving(true);
        setError(null);
        try {
            const updated = await updateSettings({
                revertToDefaults: false,
                tasks: [{
                    taskType: 'SUGGESTION_BATCH',
                    primaryModel: draftPrimary,
                    fallbackModels: draftFallbacks,
                }],
            });
            setData(updated);
            const suggestionTask = updated.tasks.find((item) => item.taskType === 'SUGGESTION_BATCH');
            setDraftPrimary(suggestionTask?.primaryModel ?? '');
            setDraftFallbacks(suggestionTask?.fallbackModels ?? []);
        } catch (err) {
            const parsed = parseApiError(err);
            setError({ code: parsed.errorCode, message: parsed.message, traceId: parsed.traceId });
        } finally {
            setSaving(false);
        }
    };

    const handleRevert = async () => {
        if (!controlsEnabled) {
            return;
        }
        setSaving(true);
        setError(null);
        try {
            const updated = await updateSettings({
                revertToDefaults: true,
                tasks: [],
            });
            setData(updated);
            const suggestionTask = updated.tasks.find((item) => item.taskType === 'SUGGESTION_BATCH');
            setDraftPrimary(suggestionTask?.primaryModel ?? '');
            setDraftFallbacks(suggestionTask?.fallbackModels ?? []);
        } catch (err) {
            const parsed = parseApiError(err);
            setError({ code: parsed.errorCode, message: parsed.message, traceId: parsed.traceId });
        } finally {
            setSaving(false);
        }
    };

    const handleTest = async () => {
        setTesting(true);
        setError(null);
        try {
            const response = await testCall('SUGGESTION_BATCH');
            setTestResult(response);
            await refresh();
        } catch (err) {
            const parsed = parseApiError(err);
            setError({ code: parsed.errorCode, message: parsed.message, traceId: parsed.traceId });
        } finally {
            setTesting(false);
        }
    };

    if (loading) {
        return (
            <div className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
                <p className="text-sm text-slate-400">Loading AI model settings...</p>
            </div>
        );
    }

    return (
        <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
            <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                    <h3 className="text-lg font-semibold text-white">{title}</h3>
                    <p className="text-xs text-slate-400">{subtitle}</p>
                </div>
                <span className={`rounded-full border px-2.5 py-1 text-xs font-bold ${
                    modeLabel === 'LIVE'
                        ? 'border-emerald-700/50 bg-emerald-700/20 text-emerald-200'
                        : 'border-blue-700/50 bg-blue-700/20 text-blue-200'
                }`}>
                    {modeLabel}
                </span>
            </div>

            {error && (
                <div className="mt-4 rounded border border-rose-700/50 bg-rose-900/30 px-3 py-2 text-xs text-rose-200">
                    <p className="font-semibold">{error.code}: {error.message}</p>
                    {error.traceId && <p className="mt-1 font-mono">Trace: {error.traceId}</p>}
                </div>
            )}

            {!controlsEnabled && (
                <div className="mt-4 rounded border border-amber-700/50 bg-amber-900/30 px-3 py-2 text-xs text-amber-200">
                    Controls are disabled by backend config. Runtime uses defaults only.
                </div>
            )}

            <div className="mt-4 rounded-lg border border-slate-700 bg-slate-900/40 p-3">
                <p className="text-xs font-semibold uppercase tracking-wider text-slate-400">Allowlisted Models</p>
                <div className="mt-2 flex flex-wrap gap-2">
                    {allowlist.map((model) => (
                        <span
                            key={model}
                            className="rounded border border-slate-600 bg-slate-800 px-2 py-1 text-[11px] font-mono text-slate-300"
                        >
                            {model}
                        </span>
                    ))}
                </div>
            </div>

            <div className="mt-4 space-y-3">
                <div>
                    <label
                        htmlFor={`${modeLabel.toLowerCase()}-suggestion-primary`}
                        className="mb-1 block text-xs font-semibold uppercase tracking-wider text-slate-400"
                    >
                        SUGGESTION_BATCH Primary
                    </label>
                    <select
                        id={`${modeLabel.toLowerCase()}-suggestion-primary`}
                        value={draftPrimary}
                        disabled={!controlsEnabled || saving}
                        onChange={(event) => {
                            const nextPrimary = event.target.value;
                            setDraftPrimary(nextPrimary);
                            setDraftFallbacks((current) => current.filter((model) => model !== nextPrimary));
                        }}
                        className="w-full rounded border border-slate-600 bg-slate-900 px-3 py-2 text-sm text-white disabled:opacity-60"
                    >
                        {allowlist.map((model) => (
                            <option key={model} value={model}>{model}</option>
                        ))}
                    </select>
                </div>

                <div>
                    <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-400">Fallback Models</p>
                    <div className="space-y-1 rounded border border-slate-700 bg-slate-900/50 p-2">
                        {allowlist.filter((model) => model !== draftPrimary).map((model) => (
                            <label key={model} className="flex items-center gap-2 text-xs text-slate-300">
                                <input
                                    type="checkbox"
                                    checked={draftFallbacks.includes(model)}
                                    disabled={!controlsEnabled || saving}
                                    onChange={(event) => handleFallbackToggle(model, event.target.checked)}
                                />
                                <span className="font-mono">{model}</span>
                            </label>
                        ))}
                    </div>
                </div>
            </div>

            <div className="mt-4 rounded-lg border border-slate-700 bg-slate-900/40 p-3 text-xs">
                <p className="font-semibold uppercase tracking-wider text-slate-400">Last Call</p>
                <div className="mt-2 grid grid-cols-1 gap-1 text-slate-300 sm:grid-cols-2">
                    <p>Status: <span className="font-semibold">{task?.lastCall?.status ?? 'N/A'}</span></p>
                    <p>Latency: {task?.lastCall?.latencyMs ?? 'N/A'} ms</p>
                    <p className="font-mono">Trace: {task?.lastCall?.traceId ?? 'N/A'}</p>
                    <p className="font-mono">Model: {task?.lastCall?.modelUsed ?? 'N/A'}</p>
                    <p>Called At: {task?.lastCall?.calledAt ? new Date(task.lastCall.calledAt).toLocaleString() : 'N/A'}</p>
                </div>
            </div>

            {testResult && (
                <div className="mt-3 rounded border border-cyan-700/50 bg-cyan-900/25 px-3 py-2 text-xs text-cyan-200">
                    <p className="font-semibold">
                        Test OK via {testResult.modelUsed}
                    </p>
                    <p className="font-mono">Trace: {testResult.traceId}</p>
                </div>
            )}

            <div className="mt-4 flex flex-col gap-2 sm:flex-row">
                <button
                    type="button"
                    onClick={handleTest}
                    disabled={testing}
                    className="flex-1 rounded border border-cyan-700/60 bg-cyan-800/30 px-3 py-2 text-sm font-semibold text-cyan-100 hover:bg-cyan-700/40 disabled:opacity-60"
                >
                    {testing ? 'Testing...' : 'Test call'}
                </button>
                <button
                    type="button"
                    onClick={handleRevert}
                    disabled={!controlsEnabled || saving}
                    className="flex-1 rounded border border-amber-700/60 bg-amber-800/30 px-3 py-2 text-sm font-semibold text-amber-100 hover:bg-amber-700/40 disabled:opacity-60"
                >
                    {saving ? 'Saving...' : 'Revert to defaults'}
                </button>
                <button
                    type="button"
                    onClick={handleSave}
                    disabled={!controlsEnabled || !hasChanges || saving}
                    className="flex-1 rounded border border-indigo-600 bg-indigo-600 px-3 py-2 text-sm font-semibold text-white hover:bg-indigo-500 disabled:opacity-60"
                >
                    {saving ? 'Saving...' : 'Save'}
                </button>
            </div>
        </section>
    );
}
