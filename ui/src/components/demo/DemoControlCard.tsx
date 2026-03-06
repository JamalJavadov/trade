import type { DemoStatusResponse } from '../../api/demoApi';

interface DemoControlCardProps {
    status: DemoStatusResponse | null;
    busyAction: string | null;
    onEnable: () => void;
    onDisable: () => void;
    onRunOnce: () => void;
    onReset: () => void;
    enableDisableAllowed: boolean;
    enableDisableTooltip?: string;
    resetAllowed: boolean;
    resetTooltip?: string;
}

function StatusPill({ label, active }: { label: string; active: boolean }) {
    return (
        <span
            className={`inline-flex items-center rounded-full border px-3 py-1 text-xs font-bold tracking-wider ${
                active
                    ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-300'
                    : 'border-slate-600 bg-slate-700/40 text-slate-300'
            }`}
        >
            {label}
        </span>
    );
}

export function DemoControlCard({
    status,
    busyAction,
    onEnable,
    onDisable,
    onRunOnce,
    onReset,
    enableDisableAllowed,
    enableDisableTooltip,
    resetAllowed,
    resetTooltip,
}: DemoControlCardProps) {
    const disabled = busyAction !== null;

    return (
        <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
            <h2 className="text-lg font-semibold text-white">Demo Control</h2>
            <p className="mt-1 text-xs text-slate-400">Paper Trading / Learning Mode</p>

            <div className="mt-4 flex flex-wrap items-center gap-2">
                <StatusPill label={`STATUS: ${status?.enabled ? 'ENABLED' : 'DISABLED'}`} active={Boolean(status?.enabled)} />
                <StatusPill label={`ENGINE: ${status?.running ? 'RUNNING' : 'IDLE'}`} active={Boolean(status?.running)} />
            </div>

            <div className="mt-5 grid grid-cols-2 gap-3">
                <button
                    type="button"
                    onClick={onEnable}
                    disabled={disabled || status?.enabled || !enableDisableAllowed}
                    title={enableDisableTooltip}
                    className="rounded-lg border border-emerald-600/60 bg-emerald-700/20 px-3 py-2 text-sm font-semibold text-emerald-200 hover:bg-emerald-700/30 disabled:opacity-50"
                >
                    {busyAction === 'enable' ? 'Enabling...' : 'Enable'}
                </button>
                <button
                    type="button"
                    onClick={onDisable}
                    disabled={disabled || !status?.enabled || !enableDisableAllowed}
                    title={enableDisableTooltip}
                    className="rounded-lg border border-slate-600 bg-slate-700 px-3 py-2 text-sm font-semibold text-slate-100 hover:bg-slate-600 disabled:opacity-50"
                >
                    {busyAction === 'disable' ? 'Disabling...' : 'Disable'}
                </button>
                <button
                    type="button"
                    onClick={onRunOnce}
                    disabled={disabled || !status?.enabled}
                    className="rounded-lg border border-blue-600/70 bg-blue-700/20 px-3 py-2 text-sm font-semibold text-blue-200 hover:bg-blue-700/30 disabled:opacity-50"
                >
                    {busyAction === 'runOnce' ? 'Running...' : 'Run Once'}
                </button>
                <button
                    type="button"
                    onClick={onReset}
                    disabled={disabled || !resetAllowed}
                    title={resetTooltip}
                    className="rounded-lg border border-rose-700/70 bg-rose-700/20 px-3 py-2 text-sm font-semibold text-rose-200 hover:bg-rose-700/30 disabled:opacity-50"
                >
                    {busyAction === 'reset' ? 'Resetting...' : 'Reset'}
                </button>
            </div>

            <p className="mt-4 rounded-lg border border-slate-700 bg-slate-900/50 p-3 text-xs text-slate-300">
                Demo Trading is isolated. It does not affect live scans or live AI.
            </p>
        </section>
    );
}
