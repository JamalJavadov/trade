import type { DemoTradeRow } from '../../api/demoApi';
import { formatDateTime, formatSignedMoney, formatSignedR, formatTimeInTrade } from './demoFormat';

export type DemoTradeFilter = 'ALL' | 'OPEN' | 'CLOSED';

interface DemoTradesTableProps {
    rows: DemoTradeRow[];
    total: number;
    page: number;
    pageSize: number;
    loading: boolean;
    filter: DemoTradeFilter;
    onFilterChange: (filter: DemoTradeFilter) => void;
    onPageChange: (page: number) => void;
    onRowClick: (tradeId: string) => void;
}

export function DemoTradesTable({
    rows,
    total,
    page,
    pageSize,
    loading,
    filter,
    onFilterChange,
    onPageChange,
    onRowClick,
}: DemoTradesTableProps) {
    const totalPages = Math.max(1, Math.ceil(total / pageSize));

    return (
        <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
            <div className="flex flex-wrap items-center justify-between gap-3">
                <h2 className="text-lg font-semibold text-white">Trades Table</h2>
                <div className="inline-flex rounded-lg border border-slate-700 bg-slate-900 p-1">
                    {(['ALL', 'OPEN', 'CLOSED'] as DemoTradeFilter[]).map((option) => (
                        <button
                            key={option}
                            type="button"
                            onClick={() => onFilterChange(option)}
                            className={`rounded px-3 py-1 text-xs font-semibold ${
                                option === filter ? 'bg-blue-600 text-white' : 'text-slate-300 hover:bg-slate-700'
                            }`}
                        >
                            {option}
                        </button>
                    ))}
                </div>
            </div>

            <div className="mt-4 overflow-x-auto rounded-lg border border-slate-700">
                <table className="w-full min-w-[880px] text-left text-sm">
                    <thead className="bg-slate-900/70 text-xs uppercase tracking-wider text-slate-400">
                        <tr>
                            <th className="px-3 py-2">Time</th>
                            <th className="px-3 py-2">Symbol</th>
                            <th className="px-3 py-2">Side</th>
                            <th className="px-3 py-2">Close Reason</th>
                            <th className="px-3 py-2 text-right">PNL USDT</th>
                            <th className="px-3 py-2 text-right">R Multiple</th>
                            <th className="px-3 py-2 text-right">Duration</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-700 bg-slate-900/30 text-slate-200">
                        {loading && (
                            <tr>
                                <td colSpan={7} className="px-3 py-6 text-center text-slate-400">Loading demo trades...</td>
                            </tr>
                        )}

                        {!loading && rows.length === 0 && (
                            <tr>
                                <td colSpan={7} className="px-3 py-6 text-center text-slate-400">No demo trades found.</td>
                            </tr>
                        )}

                        {!loading && rows.map((trade) => (
                            <tr
                                key={trade.id}
                                className="cursor-pointer hover:bg-slate-700/40"
                                onClick={() => onRowClick(trade.id)}
                            >
                                <td className="px-3 py-3">{formatDateTime(trade.openedAt ?? trade.closedAt)}</td>
                                <td className="px-3 py-3 font-semibold text-white">{trade.symbol}</td>
                                <td className="px-3 py-3">
                                    <span className={`font-semibold ${trade.side === 'LONG' ? 'text-emerald-300' : 'text-rose-300'}`}>
                                        {trade.side}
                                    </span>
                                </td>
                                <td className="px-3 py-3">{trade.closeReason ?? '-'}</td>
                                <td className="px-3 py-3 text-right font-mono">{formatSignedMoney(trade.pnlUsdt)}</td>
                                <td className="px-3 py-3 text-right font-mono">{formatSignedR(trade.rMultiple)}</td>
                                <td className="px-3 py-3 text-right">{formatTimeInTrade(trade.openedAt, trade.closedAt)}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            <div className="mt-4 flex items-center justify-between text-sm text-slate-300">
                <p>
                    Showing page {Math.min(page + 1, totalPages)} of {totalPages} ({total} rows)
                </p>
                <div className="flex items-center gap-2">
                    <button
                        type="button"
                        onClick={() => onPageChange(Math.max(0, page - 1))}
                        disabled={page <= 0 || loading}
                        className="rounded border border-slate-600 bg-slate-700 px-3 py-1 hover:bg-slate-600 disabled:opacity-50"
                    >
                        Previous
                    </button>
                    <button
                        type="button"
                        onClick={() => onPageChange(Math.min(totalPages - 1, page + 1))}
                        disabled={page >= totalPages - 1 || loading}
                        className="rounded border border-slate-600 bg-slate-700 px-3 py-1 hover:bg-slate-600 disabled:opacity-50"
                    >
                        Next
                    </button>
                </div>
            </div>
        </section>
    );
}
