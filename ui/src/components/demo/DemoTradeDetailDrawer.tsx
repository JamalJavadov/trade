import { useEffect, useMemo, useState } from 'react';
import { Copy, X } from 'lucide-react';
import { demoApi, type DemoTradeDetail } from '../../api/demoApi';
import { formatDateTime, formatDecimal, formatMoney, formatSignedMoney, formatSignedR, toNumber } from './demoFormat';

interface DemoTradeDetailDrawerProps {
    tradeId: string | null;
    onClose: () => void;
}

function ValueCell({ label, value }: { label: string; value: string }) {
    return (
        <div className="rounded border border-slate-700 bg-slate-900/40 p-2">
            <p className="text-xs uppercase tracking-wider text-slate-500">{label}</p>
            <p className="mt-1 text-sm font-medium text-slate-100">{value}</p>
        </div>
    );
}

export function DemoTradeDetailDrawer({ tradeId, onClose }: DemoTradeDetailDrawerProps) {
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [detail, setDetail] = useState<DemoTradeDetail | null>(null);

    useEffect(() => {
        if (!tradeId) {
            return;
        }

        let active = true;
        const prepTimer = window.setTimeout(() => {
            if (!active) {
                return;
            }
            setLoading(true);
            setError(null);
        }, 0);

        demoApi.getTrade(tradeId)
            .then((response) => {
                if (!active) {
                    return;
                }
                setDetail(response);
            })
            .catch((err: unknown) => {
                if (!active) {
                    return;
                }
                setError(err instanceof Error ? err.message : 'Failed to load trade details');
            })
            .finally(() => {
                if (!active) {
                    return;
                }
                setLoading(false);
            });

        return () => {
            active = false;
            window.clearTimeout(prepTimer);
        };
    }, [tradeId]);

    const snapshotPretty = useMemo(() => {
        if (!detail?.snapshotJson) {
            return 'N/A';
        }

        try {
            const parsed = JSON.parse(detail.snapshotJson);
            return JSON.stringify(parsed, null, 2);
        } catch {
            return detail.snapshotJson;
        }
    }, [detail?.snapshotJson]);

    const approxSlippageBps = useMemo(() => {
        if (!detail?.snapshotJson) {
            return null;
        }

        try {
            const parsed = JSON.parse(detail.snapshotJson) as Record<string, unknown>;
            const liveMark = toNumber(parsed.liveMarkAtEntry as number | string | null | undefined);
            const entry = toNumber(detail.entryPrice);

            if (liveMark === null || entry === null || liveMark === 0) {
                return null;
            }

            return Math.abs(((entry - liveMark) / liveMark) * 10000);
        } catch {
            return null;
        }
    }, [detail]);

    const preflightSummary = useMemo(() => {
        if (!detail?.snapshotJson) {
            return { liveMarkAtEntry: null as number | null, rrLiveAtEntry: null as number | null };
        }
        try {
            const parsed = JSON.parse(detail.snapshotJson) as Record<string, unknown>;
            return {
                liveMarkAtEntry: toNumber(parsed.liveMarkAtEntry as number | string | null | undefined),
                rrLiveAtEntry: toNumber(parsed.rrLive as number | string | null | undefined),
            };
        } catch {
            return { liveMarkAtEntry: null as number | null, rrLiveAtEntry: null as number | null };
        }
    }, [detail?.snapshotJson]);

    const handleCopySnapshot = () => {
        if (!detail?.snapshotJson) {
            return;
        }
        void navigator.clipboard.writeText(snapshotPretty);
    };

    if (!tradeId) {
        return null;
    }

    return (
        <div className="fixed inset-0 z-50 flex justify-end">
            <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={onClose} />

            <div className="relative z-10 h-full w-full max-w-2xl overflow-y-auto border-l border-slate-700 bg-slate-800 shadow-2xl">
                <div className="sticky top-0 flex items-center justify-between border-b border-slate-700 bg-slate-900/95 px-5 py-4">
                    <div>
                        <h2 className="text-lg font-semibold text-white">Demo Trade Detail</h2>
                        <p className="text-xs text-slate-400">{tradeId}</p>
                    </div>
                    <button
                        type="button"
                        onClick={onClose}
                        className="rounded border border-slate-600 bg-slate-700 p-2 text-slate-200 hover:bg-slate-600"
                    >
                        <X size={16} />
                    </button>
                </div>

                <div className="space-y-4 p-5">
                    {loading && <p className="text-sm text-slate-400">Loading trade details...</p>}
                    {error && <p className="rounded border border-rose-700/60 bg-rose-900/30 p-3 text-sm text-rose-200">{error}</p>}

                    {detail && (
                        <>
                            <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
                                <ValueCell label="Symbol" value={detail.symbol} />
                                <ValueCell label="Side" value={detail.side} />
                                <ValueCell label="Status" value={detail.status} />
                                <ValueCell label="Opened At" value={formatDateTime(detail.openedAt)} />
                                <ValueCell label="Closed At" value={formatDateTime(detail.closedAt)} />
                                <ValueCell label="Close Reason" value={detail.closeReason ?? 'N/A'} />
                                <ValueCell label="Entry" value={formatDecimal(detail.entryPrice, 6)} />
                                <ValueCell label="SL" value={formatDecimal(detail.currentSlPrice ?? detail.slPrice, 6)} />
                                <ValueCell label="TP1" value={formatDecimal(detail.tp1Price, 6)} />
                                <ValueCell label="TP2" value={formatDecimal(detail.tp2Price, 6)} />
                                <ValueCell label="TP3" value={formatDecimal(detail.tp3Price, 6)} />
                                <ValueCell label="Leverage" value={detail.leverage ? `${detail.leverage}x` : 'N/A'} />
                                <ValueCell label="Qty" value={formatDecimal(detail.qty, 6)} />
                                <ValueCell label="Remaining Qty" value={formatDecimal(detail.remainingQty, 6)} />
                                <ValueCell label="Pnl USDT" value={formatSignedMoney(detail.pnlUsdt)} />
                                <ValueCell label="R Multiple" value={formatSignedR(detail.rMultiple)} />
                                <ValueCell label="Live Mark @ Entry" value={formatDecimal(preflightSummary.liveMarkAtEntry, 6)} />
                                <ValueCell label="RRLive @ Entry" value={formatDecimal(preflightSummary.rrLiveAtEntry, 4)} />
                            </div>

                            <div className="rounded-lg border border-slate-700 bg-slate-900/40 p-4">
                                <h3 className="text-sm font-semibold text-slate-100">Fees / Slippage</h3>
                                <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
                                    <ValueCell label="Entry Fee" value={formatMoney(detail.entryFeeUsdt)} />
                                    <ValueCell label="Exit Fee" value={formatMoney(detail.exitFeeUsdt)} />
                                    <ValueCell label="Total Fees" value={formatMoney(detail.totalFeesUsdt)} />
                                    <ValueCell
                                        label="Approx Slippage (bps)"
                                        value={approxSlippageBps !== null ? approxSlippageBps.toFixed(2) : 'N/A'}
                                    />
                                </div>
                            </div>

                            <details className="rounded-lg border border-slate-700 bg-slate-900/40 p-4" open>
                                <summary className="cursor-pointer text-sm font-semibold text-slate-100">
                                    Snapshot JSON
                                </summary>
                                <div className="mt-3 flex justify-end">
                                    <button
                                        type="button"
                                        onClick={handleCopySnapshot}
                                        className="inline-flex items-center gap-1 rounded border border-slate-600 bg-slate-700 px-3 py-1 text-xs text-slate-100 hover:bg-slate-600"
                                    >
                                        <Copy size={14} /> Copy
                                    </button>
                                </div>
                                <pre className="mt-3 max-h-72 overflow-auto rounded border border-slate-700 bg-slate-950/60 p-3 text-xs text-emerald-300">
                                    {snapshotPretty}
                                </pre>
                            </details>
                        </>
                    )}
                </div>
            </div>
        </div>
    );
}
