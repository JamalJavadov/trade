import { AlertTriangle, ShieldAlert } from 'lucide-react';
import type { RecommendationDTO } from '../api/client';
import type { LiveTradingPreflightDTO } from '../api/liveTradingApi';

interface LiveExecutionConfirmModalProps {
    isOpen: boolean;
    loading: boolean;
    recommendation: RecommendationDTO;
    preflight: LiveTradingPreflightDTO | null;
    operatorNote: string;
    onOperatorNoteChange: (value: string) => void;
    onConfirm: () => void;
    onCancel: () => void;
}

export function LiveExecutionConfirmModal({
    isOpen,
    loading,
    recommendation,
    preflight,
    operatorNote,
    onOperatorNoteChange,
    onConfirm,
    onCancel,
}: LiveExecutionConfirmModalProps) {
    if (!isOpen) {
        return null;
    }

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4 backdrop-blur-sm">
            <div className="w-full max-w-2xl overflow-hidden rounded-2xl border border-red-800/60 bg-slate-950 shadow-2xl">
                <div className="border-b border-red-800/50 bg-red-950/40 p-6">
                    <div className="flex items-start gap-3">
                        <ShieldAlert className="mt-0.5 shrink-0 text-red-300" size={28} />
                        <div>
                            <h2 className="text-2xl font-bold text-white">Confirm Real Binance Execution</h2>
                            <p className="mt-2 text-sm text-red-100/80">
                                This action is manual-button-triggered only. If preflight passes, the backend will try
                                to submit live Binance Futures orders.
                            </p>
                        </div>
                    </div>
                </div>

                <div className="space-y-5 p-6">
                    <div className="grid gap-3 md:grid-cols-4">
                        <div className="rounded-lg border border-slate-800 bg-slate-900/70 p-3">
                            <p className="text-xs uppercase tracking-wider text-slate-400">Symbol</p>
                            <p className="mt-2 text-lg font-semibold text-white">{recommendation.symbol}</p>
                        </div>
                        <div className="rounded-lg border border-slate-800 bg-slate-900/70 p-3">
                            <p className="text-xs uppercase tracking-wider text-slate-400">Side</p>
                            <p className="mt-2 text-lg font-semibold text-white">{recommendation.side}</p>
                        </div>
                        <div className="rounded-lg border border-slate-800 bg-slate-900/70 p-3">
                            <p className="text-xs uppercase tracking-wider text-slate-400">Quantity</p>
                            <p className="mt-2 font-mono text-lg text-white">
                                {preflight?.exchangeValidation.quantity ?? recommendation.entryOrder?.quantity ?? 'n/a'}
                            </p>
                        </div>
                        <div className="rounded-lg border border-slate-800 bg-slate-900/70 p-3">
                            <p className="text-xs uppercase tracking-wider text-slate-400">Capability</p>
                            <p className={`mt-2 text-lg font-semibold ${preflight?.runtime.liveExecutionEnabled ? 'text-emerald-300' : 'text-rose-300'}`}>
                                {preflight?.runtime.liveExecutionEnabled ? 'ENABLED' : 'DISABLED'}
                            </p>
                        </div>
                    </div>

                    {preflight?.blockedReasons.length ? (
                        <div className="rounded-lg border border-rose-700/60 bg-rose-950/30 p-4 text-sm text-rose-100">
                            <p className="font-semibold text-rose-200">Current blocker</p>
                            <p className="mt-2 font-mono text-xs text-rose-300">{preflight.blockedReasons[0]?.code}</p>
                            <p className="mt-1">{preflight.blockedReasons[0]?.message}</p>
                        </div>
                    ) : null}

                    <div className="rounded-lg border border-amber-700/60 bg-amber-950/30 p-4 text-sm text-amber-100">
                        <div className="flex items-start gap-3">
                            <AlertTriangle className="mt-0.5 shrink-0 text-amber-300" size={18} />
                            <div>
                                <p className="font-semibold text-amber-200">High risk warning</p>
                                <p className="mt-1">
                                    Review the recommendation, live placeability, and blocked reasons before confirming.
                                    No scan or background process places live orders. Only this confirmed action can do it.
                                </p>
                            </div>
                        </div>
                    </div>

                    <label className="block">
                        <span className="text-xs uppercase tracking-wider text-slate-400">Operator note (optional)</span>
                        <textarea
                            rows={3}
                            value={operatorNote}
                            onChange={(event) => onOperatorNoteChange(event.target.value)}
                            placeholder="Optional audit note for this manual execution attempt."
                            className="mt-2 w-full rounded-lg border border-slate-700 bg-slate-900 px-3 py-2 text-sm text-slate-100"
                        />
                    </label>

                    <div className="flex flex-wrap justify-end gap-3">
                        <button
                            type="button"
                            onClick={onCancel}
                            disabled={loading}
                            className="rounded-lg border border-slate-700 bg-slate-900 px-4 py-2 text-sm font-semibold text-slate-200 hover:bg-slate-800 disabled:opacity-50"
                        >
                            Cancel
                        </button>
                        <button
                            type="button"
                            onClick={onConfirm}
                            disabled={loading}
                            className="rounded-lg border border-red-700/70 bg-red-700 px-4 py-2 text-sm font-semibold text-white hover:bg-red-600 disabled:opacity-50"
                        >
                            {loading ? 'Submitting...' : 'Open Real Order on Binance'}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
