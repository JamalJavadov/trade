import type { DemoAiSuggestionLatest } from '../../api/demoApi';

interface DemoAiSuggestionsCardProps {
    suggestions: DemoAiSuggestionLatest | null;
    loading: boolean;
    actionLoading: boolean;
    onAcceptClick: () => void;
    onRejectClick: () => void;
    actionsDisabled?: boolean;
    actionsDisabledReason?: string;
}

function RiskBadge({ risk }: { risk: string }) {
    const upper = risk.toUpperCase();
    const className = upper === 'HIGH'
        ? 'border-rose-700/60 bg-rose-700/20 text-rose-200'
        : upper === 'MEDIUM'
            ? 'border-amber-700/60 bg-amber-700/20 text-amber-200'
            : 'border-emerald-700/60 bg-emerald-700/20 text-emerald-200';

    return <span className={`inline-flex rounded border px-2 py-0.5 text-xs font-semibold ${className}`}>{upper}</span>;
}

export function DemoAiSuggestionsCard({
    suggestions,
    loading,
    actionLoading,
    onAcceptClick,
    onRejectClick,
    actionsDisabled = false,
    actionsDisabledReason,
}: DemoAiSuggestionsCardProps) {
    const batch = suggestions?.batch;
    const hasProposed = Boolean(batch && batch.status === 'PROPOSED');

    return (
        <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
            <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-lg font-semibold text-white">Demo AI Suggestions</h2>
                <span className="rounded border border-blue-700/50 bg-blue-700/20 px-2 py-1 text-xs font-semibold text-blue-200">
                    Active Config v{suggestions?.activeConfigVersion?.version ?? 'N/A'}
                </span>
            </div>

            {loading && <p className="mt-4 text-sm text-slate-400">Loading demo AI suggestions...</p>}

            {!loading && hasProposed && batch && (
                <>
                    <div className="mt-4 rounded-lg border border-slate-700 bg-slate-900/40 p-3">
                        <p className="text-sm font-semibold text-slate-100">Batch Summary</p>
                        <p className="mt-1 text-sm text-slate-300">{batch.summary}</p>
                        <p className="mt-2 text-xs text-slate-500">Created: {new Date(batch.createdAt).toLocaleString()}</p>
                    </div>

                    <div className="mt-4 overflow-x-auto rounded-lg border border-slate-700">
                        <table className="w-full min-w-[720px] text-left text-sm">
                            <thead className="bg-slate-900/70 text-xs uppercase tracking-wider text-slate-400">
                                <tr>
                                    <th className="px-3 py-2">Key</th>
                                    <th className="px-3 py-2">Proposed Value</th>
                                    <th className="px-3 py-2">Risk</th>
                                    <th className="px-3 py-2">Reason</th>
                                    <th className="px-3 py-2">Impact Hypothesis</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-700 bg-slate-900/30 text-slate-200">
                                {suggestions?.items.map((item) => (
                                    <tr key={`${item.batchId}-${item.key}`}>
                                        <td className="px-3 py-3 align-top font-mono text-xs text-blue-300">{item.key}</td>
                                        <td className="px-3 py-3 align-top font-mono text-xs text-amber-300">{item.proposedValue}</td>
                                        <td className="px-3 py-3 align-top"><RiskBadge risk={item.riskOfChange} /></td>
                                        <td className="px-3 py-3 align-top">
                                            <details>
                                                <summary className="cursor-pointer text-xs text-slate-300">Expand</summary>
                                                <p className="mt-2 text-xs text-slate-300 whitespace-pre-wrap">{item.reason}</p>
                                            </details>
                                        </td>
                                        <td className="px-3 py-3 align-top">
                                            <details>
                                                <summary className="cursor-pointer text-xs text-slate-300">Expand</summary>
                                                <p className="mt-2 text-xs text-slate-300 whitespace-pre-wrap">{item.impactHypothesis}</p>
                                            </details>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    <div className="mt-4 flex flex-col gap-3 sm:flex-row">
                        <button
                            type="button"
                            onClick={onRejectClick}
                            disabled={actionLoading || actionsDisabled}
                            title={actionsDisabledReason}
                            className="flex-1 rounded-lg border border-slate-600 bg-slate-700 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-600 disabled:opacity-60"
                        >
                            {actionLoading ? 'Submitting...' : 'Reject'}
                        </button>
                        <button
                            type="button"
                            onClick={onAcceptClick}
                            disabled={actionLoading || actionsDisabled}
                            title={actionsDisabledReason}
                            className="flex-1 rounded-lg border border-blue-600 bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-500 disabled:opacity-60"
                        >
                            {actionLoading ? 'Submitting...' : 'Accept'}
                        </button>
                    </div>
                </>
            )}

            {!loading && !hasProposed && (
                <div className="mt-4 rounded-lg border border-slate-700 bg-slate-900/40 p-4 text-sm text-slate-300">
                    Next suggestions appear after every 10 demo trades.
                </div>
            )}
        </section>
    );
}
