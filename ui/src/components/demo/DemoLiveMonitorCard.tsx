import type { DemoTradeDetail, DemoTradeRow } from '../../api/demoApi';
import { formatDateTime, formatTimeInTrade } from './demoFormat';

interface DemoLiveMonitorCardProps {
    enabled: boolean;
    loading: boolean;
    openTrades: DemoTradeRow[];
    openTradeDetails: Record<string, DemoTradeDetail>;
}

function PriceValue({ value }: { value: number | string | null | undefined }) {
    if (value === null || value === undefined) {
        return <span className="text-slate-500">N/A</span>;
    }

    const parsed = typeof value === 'number' ? value : Number(value);
    if (!Number.isFinite(parsed)) {
        return <span className="text-slate-500">N/A</span>;
    }

    return <span className="font-mono text-slate-100">{parsed.toFixed(6)}</span>;
}

export function DemoLiveMonitorCard({ enabled, loading, openTrades, openTradeDetails }: DemoLiveMonitorCardProps) {
    return (
        <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
            <h2 className="text-lg font-semibold text-white">Live Demo Monitor</h2>

            {!enabled && (
                <p className="mt-4 rounded-lg border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-300">
                    Demo runtime is disabled. Enable Demo Trading to monitor open paper trades.
                </p>
            )}

            {enabled && loading && (
                <p className="mt-4 text-sm text-slate-400">Refreshing open demo trades...</p>
            )}

            {enabled && !loading && openTrades.length === 0 && (
                <p className="mt-4 rounded-lg border border-slate-700 bg-slate-900/40 p-3 text-sm text-slate-300">
                    No open demo trades at the moment.
                </p>
            )}

            {enabled && openTrades.length > 0 && (
                <div className="mt-4 space-y-3">
                    {openTrades.map((trade) => {
                        const detail = openTradeDetails[trade.id];

                        return (
                            <div key={trade.id} className="rounded-lg border border-slate-700 bg-slate-900/40 p-4">
                                <div className="flex flex-wrap items-center justify-between gap-2">
                                    <div className="flex items-center gap-2">
                                        <span className="text-sm font-semibold text-white">{trade.symbol}</span>
                                        <span
                                            className={`rounded border px-2 py-0.5 text-xs font-bold ${
                                                trade.side === 'LONG'
                                                    ? 'border-emerald-600/50 bg-emerald-600/20 text-emerald-200'
                                                    : 'border-rose-600/50 bg-rose-600/20 text-rose-200'
                                            }`}
                                        >
                                            {trade.side}
                                        </span>
                                    </div>
                                    <span className="text-xs text-slate-400">Opened: {formatDateTime(trade.openedAt)}</span>
                                </div>

                                <div className="mt-3 grid grid-cols-2 gap-3 text-xs sm:grid-cols-4">
                                    <div className="rounded border border-slate-700 bg-slate-950/40 p-2">
                                        <p className="text-slate-500">Entry</p>
                                        <PriceValue value={detail?.entryPrice} />
                                    </div>
                                    <div className="rounded border border-slate-700 bg-slate-950/40 p-2">
                                        <p className="text-slate-500">SL</p>
                                        <PriceValue value={detail?.currentSlPrice ?? detail?.slPrice} />
                                    </div>
                                    <div className="rounded border border-slate-700 bg-slate-950/40 p-2">
                                        <p className="text-slate-500">TP1</p>
                                        <PriceValue value={detail?.tp1Price} />
                                    </div>
                                    <div className="rounded border border-slate-700 bg-slate-950/40 p-2">
                                        <p className="text-slate-500">Time In Trade</p>
                                        <p className="font-medium text-slate-100">{formatTimeInTrade(trade.openedAt)}</p>
                                    </div>
                                </div>
                            </div>
                        );
                    })}

                    <p className="text-xs text-slate-400">Monitoring... refresh every 5s.</p>
                </div>
            )}
        </section>
    );
}
