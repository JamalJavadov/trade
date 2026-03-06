import React, { useState, useEffect } from 'react';
import { ShieldAlert, Save, RefreshCw } from 'lucide-react';
import { getSettings, updateSettings, previewRisk } from '../../api/client';
import type { SettingsDTO, RiskPreviewResponseDTO } from '../../api/client';
import { parseApiError } from '../../utils/apiError';
import { usePermissions } from '../../hooks/usePermissions';
import { disabledByPermissionTooltip, firstDeniedPermission } from '../../utils/permissionUi';

export const RiskSettingsCard: React.FC = () => {
    const { can } = usePermissions();
    const [settings, setSettings] = useState<SettingsDTO | null>(null);
    const [draft, setDraft] = useState<Partial<SettingsDTO>>({});
    const [preview, setPreview] = useState<RiskPreviewResponseDTO | null>(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [validationErrors, setValidationErrors] = useState<Record<string, string>>({});
    const [saveError, setSaveError] = useState<{ errorCode: string; message: string; traceId: string | null } | null>(null);
    const blockedSettingsPermissionKey = firstDeniedPermission(
        ['settings.update', 'settings.risk_budget.update'],
        can,
    );
    const settingsEditingAllowed = blockedSettingsPermissionKey === null;
    const settingsDisabledTooltip = blockedSettingsPermissionKey
        ? disabledByPermissionTooltip(blockedSettingsPermissionKey, false)
        : undefined;

    useEffect(() => {
        loadSettings();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const loadSettings = async () => {
        try {
            setLoading(true);
            const data = await getSettings();
            setSettings(data);
            setDraft(data);
            setValidationErrors({});
            setSaveError(null);
            void fetchPreview(data);
        } catch (err: unknown) {
            console.error("Failed to load settings", err);
        } finally {
            setLoading(false);
        }
    };

    const fetchPreview = async (currentDraft: Partial<SettingsDTO>) => {
        try {
            const result = await previewRisk({
                budgetUsdt: currentDraft.budgetUsdt,
                maxBudgetPct: currentDraft.maxBudgetPct,
                equityOverrideUsdt: currentDraft.equityOverrideUsdt,
                maxEquityPct: currentDraft.maxEquityPct
            });
            setPreview(result);
        } catch (err: unknown) {
            // Error handled by global interceptor, but we can clear preview
            setPreview(null);
            console.error(err);
        }
    };

    // Debounce preview fetch when draft changes
    useEffect(() => {
        if (!settings) return;
        const timer = setTimeout(() => {
            fetchPreview(draft);
        }, 500);
        return () => clearTimeout(timer);
    }, [draft, settings]);

    const handleChange = (field: keyof SettingsDTO, value: number | null | undefined) => {
        if (!settingsEditingAllowed) {
            return;
        }
        setDraft(prev => ({ ...prev, [field]: value }));
        // Clear specific validation error if user types
        if (validationErrors[field]) {
            setValidationErrors(prev => {
                const copy = { ...prev };
                delete copy[field];
                return copy;
            });
        }
    };

    const handleSave = async () => {
        if (!settings || !settingsEditingAllowed) return;
        setSaving(true);
        setValidationErrors({});
        setSaveError(null);

        try {
            const payload: Partial<SettingsDTO> = {};

            if (isFiniteNumber(draft.budgetUsdt) && !sameNumber(draft.budgetUsdt, settings.budgetUsdt)) {
                payload.budgetUsdt = draft.budgetUsdt;
            }
            if (isFiniteNumber(draft.maxBudgetPct) && !sameNumber(draft.maxBudgetPct, settings.maxBudgetPct)) {
                payload.maxBudgetPct = draft.maxBudgetPct;
            }
            if (draft.equityOverrideUsdt === null && settings.equityOverrideUsdt != null) {
                payload.equityOverrideUsdt = null;
            } else if (isFiniteNumber(draft.equityOverrideUsdt)
                && !sameNumber(draft.equityOverrideUsdt, settings.equityOverrideUsdt)) {
                payload.equityOverrideUsdt = draft.equityOverrideUsdt;
            }

            if (Object.keys(payload).length === 0) {
                return;
            }

            const updated = await updateSettings(payload);
            setSettings(updated);
            setDraft(updated);
        } catch (err: unknown) {
            const parsed = parseApiError(err);
            setSaveError({
                errorCode: parsed.errorCode,
                message: parsed.message,
                traceId: parsed.traceId
            });
            if (parsed.errorCode === 'VALIDATION' && Object.keys(parsed.fieldErrors).length > 0) {
                setValidationErrors(parsed.fieldErrors);
            }
        } finally {
            setSaving(false);
        }
    };

    if (loading || !settings) {
        return (
            <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 h-64 flex items-center justify-center">
                <RefreshCw className="animate-spin text-slate-600" size={24} />
            </div>
        );
    }

    const hasChanges = !sameNumber(draft.budgetUsdt, settings.budgetUsdt)
        || !sameNumber(draft.maxBudgetPct, settings.maxBudgetPct)
        || !sameNumber(draft.equityOverrideUsdt, settings.equityOverrideUsdt);

    return (
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 shadow-lg">
            <div className="flex items-center gap-3 mb-6 pb-4 border-b border-slate-800">
                <div className="p-2 bg-amber-500/10 rounded-lg">
                    <ShieldAlert className="text-amber-500" size={20} />
                </div>
                <div>
                    <h3 className="text-lg font-bold text-white">Risk & Budget Controls</h3>
                    <p className="text-xs text-slate-400">Manage capital allocation limits.</p>
                </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
                {/* Inputs */}
                <div className="space-y-5">
                    {!settingsEditingAllowed && (
                        <div className="rounded border border-amber-700/50 bg-amber-900/25 px-3 py-2 text-xs text-amber-200">
                            Disabled by operator permission: {blockedSettingsPermissionKey}
                        </div>
                    )}
                    <div>
                        <label className="block text-sm font-medium text-slate-300 mb-1">
                            Trade Budget (USDT)
                        </label>
                        <input
                            type="number"
                            min="0"
                            step="100"
                            value={draft.budgetUsdt ?? ''}
                            onChange={(e) => handleChange('budgetUsdt', toOptionalNumber(e.target.value))}
                            disabled={!settingsEditingAllowed}
                            title={settingsDisabledTooltip}
                            className={`w-full bg-slate-950 border rounded-lg px-4 py-2 text-white focus:outline-none focus:ring-2 transition-all ${validationErrors.budgetUsdt ? 'border-rose-500 focus:ring-rose-500/50' : 'border-slate-800 focus:border-indigo-500 focus:ring-indigo-500/20'}`}
                        />
                        {validationErrors.budgetUsdt && (
                            <p className="text-rose-400 text-xs mt-1 font-medium">{validationErrors.budgetUsdt}</p>
                        )}
                    </div>

                    <div>
                        <label className="block text-sm font-medium text-slate-300 mb-1">
                            Max Budget Risk (%)
                        </label>
                        <div className="flex items-center gap-4">
                            <input
                                type="range"
                                min="0.1"
                                max="20"
                                step="0.1"
                                value={draft.maxBudgetPct ?? 5.0}
                                onChange={(e) => handleChange('maxBudgetPct', Number(e.target.value))}
                                disabled={!settingsEditingAllowed}
                                title={settingsDisabledTooltip}
                                className="flex-1 accent-indigo-500"
                            />
                            <span className="text-indigo-400 font-mono w-12 text-right">
                                {Number(draft.maxBudgetPct || 5.0).toFixed(1)}%
                            </span>
                        </div>
                        {validationErrors.maxBudgetPct && (
                            <p className="text-rose-400 text-xs mt-1 font-medium">{validationErrors.maxBudgetPct}</p>
                        )}
                    </div>

                    <div>
                        <label className="block text-sm font-medium text-slate-300 mb-1">
                            Equity Override (USDT)
                            <span className="text-xs text-slate-500 font-normal ml-2">(Optional fallback)</span>
                        </label>
                        <input
                            type="number"
                            min="0"
                            step="1000"
                            value={draft.equityOverrideUsdt ?? ''}
                            onChange={(e) => handleChange('equityOverrideUsdt', toOptionalNumberOrNull(e.target.value))}
                            disabled={!settingsEditingAllowed}
                            title={settingsDisabledTooltip}
                            className={`w-full bg-slate-950 border rounded-lg px-4 py-2 text-white placeholder-slate-600 focus:outline-none focus:ring-2 transition-all ${validationErrors.equityOverrideUsdt ? 'border-rose-500 focus:ring-rose-500/50' : 'border-slate-800 focus:border-indigo-500 focus:ring-indigo-500/20'}`}
                            placeholder="e.g. 50000"
                        />
                        {validationErrors.equityOverrideUsdt && (
                            <p className="text-rose-400 text-xs mt-1 font-medium">{validationErrors.equityOverrideUsdt}</p>
                        )}
                    </div>
                </div>

                {/* Preview Panel */}
                <div className="bg-slate-950/50 border border-slate-800 rounded-xl p-5 flex flex-col justify-between">
                    <div>
                        <h4 className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-4 border-b border-slate-800 pb-2">Effective Risk Preview</h4>

                        {preview ? (
                            <div className="space-y-4">
                                <div className="flex justify-between items-center text-sm">
                                    <span className="text-slate-400">From Budget:</span>
                                    <span className="text-slate-300 font-mono">${preview.riskUsdtFromBudget?.toFixed(2) || '---'}</span>
                                </div>
                                <div className="flex justify-between items-center text-sm">
                                    <span className="text-slate-400">From Equity:</span>
                                    <span className="text-slate-300 font-mono">${preview.riskUsdtFromEquity?.toFixed(2) || '---'}</span>
                                </div>

                                <div className="pt-4 border-t border-slate-800/50 mt-4">
                                    <div className="flex justify-between items-center">
                                        <span className="text-slate-300 font-medium">Enforced Max Limit:</span>
                                        <span className="text-2xl font-bold text-emerald-400 font-mono">${preview.effectiveRiskUsdt?.toFixed(2) || '0.00'}</span>
                                    </div>
                                    <div className="mt-3 text-xs text-slate-500 space-y-1">
                                        {preview.notes?.map((note, i) => (
                                            <div key={i} className="flex gap-2">
                                                <span className="opacity-50">›</span> {note}
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            </div>
                        ) : (
                            <div className="flex items-center justify-center h-32 text-slate-500 text-sm italic">
                                Computing bounds...
                            </div>
                        )}
                    </div>

                    <div className="mt-6 pt-4 border-t border-slate-800 flex justify-between items-center">
                        <div className="text-xs text-slate-500">
                            Locked invariant: Max Equity Risk = 1.0%
                        </div>
                        {saveError && (
                            <div className="mr-4 text-right">
                                <p className="text-xs text-rose-300 font-semibold">
                                    {saveError.errorCode}: {saveError.message}
                                </p>
                                {saveError.traceId && (
                                    <p className="text-[11px] text-rose-400/80 font-mono">Trace: {saveError.traceId}</p>
                                )}
                            </div>
                        )}
                        <button
                            onClick={handleSave}
                            disabled={!settingsEditingAllowed || !hasChanges || saving}
                            title={settingsDisabledTooltip}
                            className={`flex items-center gap-2 px-6 py-2 rounded-lg text-sm font-bold transition-all ${settingsEditingAllowed && hasChanges && !saving
                                ? 'bg-indigo-600 hover:bg-indigo-500 text-white shadow-lg shadow-indigo-500/20'
                                : 'bg-slate-800 text-slate-500 cursor-not-allowed'
                                }`}
                        >
                            {saving ? (
                                <RefreshCw size={16} className="animate-spin" />
                            ) : (
                                <Save size={16} />
                            )}
                            {saving ? 'Saving...' : 'Save Settings'}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
};

function toOptionalNumber(raw: string): number | undefined {
    if (raw.trim() === '') {
        return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
}

function toOptionalNumberOrNull(raw: string): number | null | undefined {
    if (raw.trim() === '') {
        return null;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
}

function isFiniteNumber(value: number | null | undefined): value is number {
    return typeof value === 'number' && Number.isFinite(value);
}

function sameNumber(left: number | null | undefined, right: number | null | undefined): boolean {
    if (left == null && right == null) {
        return true;
    }
    if (left == null || right == null) {
        return false;
    }
    return Math.abs(left - right) < 0.0000001;
}
