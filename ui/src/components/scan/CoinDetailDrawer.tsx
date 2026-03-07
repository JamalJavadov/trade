import { useEffect, useState } from 'react';
import type { SymbolEvaluationDetailDTO } from '../../types/scan';
import type { ExplanationDTO } from '../../types/scanReplay';
import { getScanEvaluationDetail, getExplanation } from '../../api/scan';
import { X, Loader2, AlertCircle, Info } from 'lucide-react';

interface CoinDetailDrawerProps {
    scanRunId: string | null;
    symbol: string | null;
    onClose: () => void;
}

export function CoinDetailDrawer({ scanRunId, symbol, onClose }: CoinDetailDrawerProps) {
    const [detail, setDetail] = useState<SymbolEvaluationDetailDTO | null>(null);
    const [explanation, setExplanation] = useState<ExplanationDTO | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!scanRunId || !symbol) {
            // Safe to clear data asynchronously if unmounting/closing
            setTimeout(() => {
                setDetail(null);
                setExplanation(null);
            }, 0);
            return;
        }

        let mounted = true;
        setTimeout(() => {
            if (mounted) {
                setLoading(true);
                setError(null);
            }
        }, 0);

        Promise.all([
            getScanEvaluationDetail(scanRunId, symbol),
            getExplanation(scanRunId, symbol).catch(e => {
                console.warn("Explanation unavailable", e);
                return null;
            })
        ]).then(([detailData, explainData]) => {
            if (mounted) {
                setDetail(detailData);
                setExplanation(explainData);
            }
        }).catch(err => {
            if (mounted) setError(err.message);
        })
            .finally(() => {
                if (mounted) setLoading(false);
            });

        return () => { mounted = false; };
    }, [scanRunId, symbol]);

    if (!symbol) return null;

    return (
        <div className={`fixed inset-0 z-50 flex justify-end transition-opacity duration-300 ${symbol ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'}`}>
            {/* Backdrop */}
            <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" onClick={onClose} />

            {/* Drawer */}
            <div className="relative w-full max-w-lg h-full bg-slate-800 border-l border-slate-700 shadow-2xl flex flex-col transform transition-transform duration-300 translate-x-0">

                {/* Header */}
                <div className="flex items-center justify-between px-6 py-4 border-b border-slate-700 bg-slate-800/80 backdrop-blur">
                    <div className="flex items-center gap-3">
                        <h2 className="text-xl font-bold text-white">{symbol}</h2>
                        {detail && (
                            <span className={`px-2 py-0.5 rounded text-[10px] font-bold tracking-wider ${detail.decision === 'VALID' ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20' :
                                detail.decision === 'DATA_ERROR' ? 'bg-rose-500/10 text-rose-400 border border-rose-500/20' :
                                    'bg-slate-700 text-slate-400 border border-slate-600'
                                }`}>
                                {detail.decision}
                            </span>
                        )}
                    </div>
                    <button onClick={onClose} className="p-2 text-slate-400 hover:text-white rounded-md hover:bg-slate-700 transition-colors">
                        <X className="w-5 h-5" />
                    </button>
                </div>

                {/* Body */}
                <div className="flex-1 overflow-y-auto p-6 custom-scrollbar">
                    {loading && (
                        <div className="flex flex-col items-center justify-center h-40 text-slate-400">
                            <Loader2 className="w-8 h-8 animate-spin mb-4 text-blue-500" />
                            <span>Loading diagnostics...</span>
                        </div>
                    )}

                    {error && (
                        <div className="bg-rose-500/10 border border-rose-500/20 text-rose-400 p-4 rounded-lg flex items-start gap-3">
                            <AlertCircle className="w-5 h-5 shrink-0 mt-0.5" />
                            <p className="text-sm">{error}</p>
                        </div>
                    )}

                    {detail && (
                        <div className="space-y-8 flex flex-col">

                            <div className="grid grid-cols-2 gap-4">
                                <HighlightBox label="Rank in Universe" value={`#${detail.rankInUniverse}`} />
                                <HighlightBox label="Skip Reason" value={detail.skipReasonCode || 'N/A'} isError={!!detail.skipReasonCode} />
                                <HighlightBox label="Side" value={detail.side} isSuccess={detail.side !== 'NONE'} />
                                <HighlightBox label="Final Score" value={detail.finalScore?.toFixed(2) || '-'} />
                                <HighlightBox label="24h Volume" value={detail.quoteVolumeUsdt ? `$${(detail.quoteVolumeUsdt / 1_000_000).toFixed(1)}M` : '-'} />
                                <HighlightBox label="Integrity" value={detail.finalIntegrityScore != null ? `${detail.finalIntegrityScore}` : '-'} isSuccess={(detail.finalIntegrityScore ?? 0) >= 80} />
                                <HighlightBox label="Gate" value={detail.recommendationEligible ? 'READY' : 'BLOCKED'} isSuccess={detail.recommendationEligible === true} isError={detail.recommendationEligible === false && detail.decision === 'VALID'} />
                                <HighlightBox label="Conflict" value={detail.conflictState || 'NONE'} isError={!!detail.conflictState && detail.conflictState !== 'NONE'} />
                                <HighlightBox label="AI Review" value={detail.aiAgreementState || detail.aiReviewStatus || 'N/A'} />
                            </div>

                            {/* Human Explainability AI Box */}
                            {explanation && (
                                <div className="bg-gradient-to-br from-indigo-900/40 to-cyan-900/20 border border-indigo-500/30 rounded-xl p-5 shadow-inner">
                                    <div className="flex items-center gap-2 mb-3 border-b border-indigo-500/20 pb-2">
                                        <Info className="w-5 h-5 text-indigo-400" />
                                        <h3 className="font-bold text-indigo-100">{explanation.headline}</h3>
                                    </div>
                                    <ul className="space-y-2 list-disc list-inside text-sm text-indigo-200/90 ml-1">
                                        {explanation.bullets.map((b, i) => (
                                            <li key={i}>{b}</li>
                                        ))}
                                    </ul>
                                </div>
                            )}

                            {/* Trade Plan Setup (If Valid) */}
                            {detail.decision === 'VALID' && (
                                <div className="bg-slate-900 rounded-xl p-4 border border-slate-700">
                                    <h3 className="text-sm font-semibold text-slate-300 mb-3 uppercase tracking-wider">Trading Plan</h3>
                                    <div className="grid grid-cols-2 gap-y-3 gap-x-6 text-sm">
                                        <PlanRow label="Entry Price" value={detail.entry?.toString() || '-'} />
                                        <PlanRow label="Stop Loss" value={detail.sl?.toString() || '-'} />
                                        <PlanRow label="Take Profit 1" value={detail.tp1?.toString() || '-'} />
                                        <PlanRow label="RR to TP1" value={detail.rrTp1 ? `${detail.rrTp1.toFixed(2)}x` : '-'} highlight />
                                    </div>
                                    <div className="mt-4 pt-4 border-t border-slate-800 flex items-center justify-between text-xs text-slate-500">
                                        <span>Metrics and quantities are in raw JSON below.</span>
                                    </div>
                                </div>
                            )}

                            {/* Raw JSONs */}
                            {detail.diagnostics && (
                                <JsonSection title="Diagnostics Snapshot" accent="border-l-emerald-500" color="text-emerald-400" value={detail.diagnostics} />
                            )}

                            {detail.metrics && (
                                <JsonSection title="Metrics Snapshot" accent="border-l-blue-500" color="text-blue-400" value={detail.metrics} />
                            )}

                            {detail.snapshot && <JsonSection title="Frozen Snapshot" accent="border-l-cyan-500" color="text-cyan-300" value={detail.snapshot} />}
                            {detail.integrity && <JsonSection title="Integrity Audit" accent="border-l-rose-500" color="text-rose-300" value={detail.integrity} />}
                            {detail.validation && <JsonSection title="Structural Validation" accent="border-l-amber-500" color="text-amber-300" value={detail.validation} />}
                            {detail.confirmation && <JsonSection title="Second-Pass Confirmation" accent="border-l-orange-500" color="text-orange-300" value={detail.confirmation} />}
                            {detail.aiReview && <JsonSection title="AI Comparative Review" accent="border-l-fuchsia-500" color="text-fuchsia-300" value={detail.aiReview} />}
                            {detail.finalGate && <JsonSection title="Final Recommendation Gate" accent="border-l-lime-500" color="text-lime-300" value={detail.finalGate} />}

                            {detail.candidateEvents && detail.candidateEvents.length > 0 && (
                                <div className="bg-slate-900/50 border border-slate-700 rounded-lg p-4">
                                    <h3 className="text-sm font-semibold text-slate-300 mb-3 uppercase tracking-wider">Candidate Stage Timeline</h3>
                                    <div className="space-y-2">
                                        {detail.candidateEvents.map((event) => (
                                            <div key={`${event.stage}-${event.seq}`} className="flex items-start justify-between gap-3 border-b border-slate-800/70 pb-2 last:border-b-0 last:pb-0">
                                                <div>
                                                    <div className="font-mono text-xs text-slate-200">{event.stage}</div>
                                                    <div className="text-[11px] text-slate-500">{new Date(event.ts).toLocaleTimeString()}</div>
                                                </div>
                                                <div className="text-right">
                                                    <div className="text-xs text-blue-300">{event.status}</div>
                                                    {event.payload && (
                                                        <div className="text-[11px] text-slate-500 truncate max-w-[220px]">
                                                            {JSON.stringify(event.payload)}
                                                        </div>
                                                    )}
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            )}

                            {(!detail.metrics && !detail.diagnostics) && detail.decision !== 'DATA_ERROR' && (
                                <p className="text-sm text-slate-500 italic">No advanced diagnostics presisted for this evaluation.</p>
                            )}
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
}

function HighlightBox({ label, value, isError, isSuccess }: { label: string, value: string, isError?: boolean, isSuccess?: boolean }) {
    return (
        <div className="bg-slate-900/50 p-3 rounded-lg border border-slate-700/50">
            <div className="text-[10px] uppercase tracking-wider text-slate-500 mb-1">{label}</div>
            <div className={`font-mono text-lg font-medium ${isError ? 'text-rose-400' : isSuccess ? 'text-emerald-400' : 'text-slate-200'}`}>
                {value}
            </div>
        </div>
    );
}

function PlanRow({ label, value, highlight }: { label: string, value: string, highlight?: boolean }) {
    return (
        <div className="flex justify-between items-center border-b border-slate-800/50 pb-1">
            <span className="text-slate-500">{label}</span>
            <span className={`font-mono ${highlight ? 'text-emerald-400 font-bold' : 'text-slate-300'}`}>{value}</span>
        </div>
    );
}

function JsonSection({
    title,
    accent,
    color,
    value,
}: {
    title: string;
    accent: string;
    color: string;
    value: unknown;
}) {
    return (
        <div>
            <h3 className="text-sm font-semibold text-slate-300 mb-2 uppercase tracking-wider flex items-center gap-2">
                {title}
            </h3>
            <pre className={`bg-slate-900/50 p-4 rounded-lg text-xs font-mono overflow-x-auto border border-slate-700 border-l-4 ${accent} ${color}`}>
                {JSON.stringify(value, null, 2)}
            </pre>
        </div>
    );
}
