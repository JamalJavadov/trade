import { Link } from 'react-router-dom';
import { useBudgetTargetAutoExecution } from '../../hooks/useBudgetTargetAutoExecution';

function formatInstant(value: string | null | undefined): string {
    if (!value) {
        return 'n/a';
    }
    return new Date(value).toLocaleString();
}

function formatMoney(value: number | null | undefined): string {
    if (value == null || Number.isNaN(value)) {
        return 'n/a';
    }
    return value.toFixed(2);
}

export function BudgetTargetAutoExecutionCard() {
    const {
        state,
        session,
        loading,
        reconnecting,
        primaryBlockedReason,
    } = useBudgetTargetAutoExecution();

    const statusTone = !session
        ? 'border-slate-700 bg-slate-900/50 text-slate-200'
        : session.status === 'RUNNING'
            ? 'border-emerald-700/50 bg-emerald-900/20 text-emerald-200'
            : (session.status === 'STOPPING' || session.status === 'TARGET_REACHED')
                ? 'border-amber-700/50 bg-amber-900/20 text-amber-200'
                : session.status === 'FAILED'
                    ? 'border-rose-700/50 bg-rose-900/20 text-rose-200'
                    : 'border-sky-700/50 bg-sky-900/20 text-sky-200';

    return (
        <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
            <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                    <h2 className="text-lg font-semibold text-white">Budget Target Auto-Execution</h2>
                    <p className="text-xs text-slate-400">
                        Simple operator summary. Open the dedicated page for start, graceful stop, health, and live trade detail.
                    </p>
                </div>
                <span className={`rounded-full border px-3 py-1 text-xs font-semibold uppercase tracking-wider ${statusTone}`}>
                    {session?.status ?? 'IDLE'}
                </span>
            </div>

            <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-3">
                <div className="rounded border border-slate-700 bg-slate-900/40 p-3">
                    <p className="text-xs text-slate-400">Active trades</p>
                    <p className="mt-2 text-lg font-semibold text-white">
                        {session?.activeTradeCount ?? 0} / {session?.activeTradeLimit ?? state?.config.maxConcurrentPositions ?? 3}
                    </p>
                </div>
                <div className="rounded border border-slate-700 bg-slate-900/40 p-3">
                    <p className="text-xs text-slate-400">Realized net PnL</p>
                    <p className="mt-2 text-lg font-semibold text-white">{formatMoney(session?.realizedNetPnlUsdt)} USDT</p>
                </div>
                <div className="rounded border border-slate-700 bg-slate-900/40 p-3">
                    <p className="text-xs text-slate-400">Last updated</p>
                    <p className="mt-2 text-sm text-white">{loading ? 'Loading...' : formatInstant(state?.serverTime)}</p>
                </div>
            </div>

            <div className={`mt-4 rounded border p-3 text-sm ${
                reconnecting
                    ? 'border-amber-700/50 bg-amber-900/20 text-amber-100'
                    : 'border-slate-700 bg-slate-950/40 text-slate-200'
            }`}>
                <p className="text-xs uppercase tracking-wider text-slate-400">Primary blocker</p>
                <p className="mt-2">{primaryBlockedReason.message}</p>
            </div>

            <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-slate-700 pt-4">
                <div className="text-xs text-slate-400">
                    Defaults still live in <Link to="/permissions" className="text-sky-300 hover:text-sky-200">Control Center</Link>.
                </div>
                <Link
                    to="/auto-session"
                    className="rounded border border-sky-700/50 bg-sky-900/20 px-3 py-1.5 text-xs font-semibold text-sky-200 hover:bg-sky-900/35"
                >
                    Open Auto Session page
                </Link>
            </div>
        </section>
    );
}
