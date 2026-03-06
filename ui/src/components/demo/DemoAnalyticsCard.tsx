import {
    Bar,
    BarChart,
    CartesianGrid,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis,
} from 'recharts';
import type { DemoAnalyticsSummary } from '../../api/demoApi';
import { formatDecimal, formatDurationMinutes, formatPercentFromRatio } from './demoFormat';

interface DemoAnalyticsCardProps {
    analytics: DemoAnalyticsSummary | null;
    lookback: 10 | 50 | 100;
    loading: boolean;
    onLookbackChange: (value: 10 | 50 | 100) => void;
}

function KpiCard({ label, value }: { label: string; value: string }) {
    return (
        <div className="rounded-lg border border-slate-700 bg-slate-900/40 p-3">
            <p className="text-xs uppercase tracking-wider text-slate-400">{label}</p>
            <p className="mt-1 text-lg font-semibold text-slate-100">{value}</p>
        </div>
    );
}

export function DemoAnalyticsCard({ analytics, lookback, loading, onLookbackChange }: DemoAnalyticsCardProps) {
    const metrics = analytics?.metrics;
    const closeReasonMap = (metrics?.closeReasonDistribution as Record<string, number> | undefined) ?? {};

    const closeReasonData = Object.entries(closeReasonMap).map(([reason, count]) => ({
        reason,
        count,
    }));

    const sideWinrateData = (analytics?.cohorts?.side ?? []).map((row) => {
        const ratio = typeof row.winRate === 'number' ? row.winRate : Number(row.winRate);
        return {
            bucket: row.bucket,
            winRatePct: Number.isFinite(ratio) ? Number((ratio * 100).toFixed(2)) : 0,
        };
    });

    const topPatterns = analytics?.topFailurePatterns ?? [];

    return (
        <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
            <div className="flex flex-wrap items-center justify-between gap-3">
                <h2 className="text-lg font-semibold text-white">Analytics</h2>
                <div className="inline-flex rounded-lg border border-slate-700 bg-slate-900 p-1">
                    {[10, 50, 100].map((value) => (
                        <button
                            key={value}
                            type="button"
                            onClick={() => onLookbackChange(value as 10 | 50 | 100)}
                            className={`rounded px-3 py-1 text-xs font-semibold ${
                                lookback === value
                                    ? 'bg-blue-600 text-white'
                                    : 'text-slate-300 hover:bg-slate-700'
                            }`}
                        >
                            {value}
                        </button>
                    ))}
                </div>
            </div>

            {loading && <p className="mt-4 text-sm text-slate-400">Loading demo analytics...</p>}

            {!loading && analytics && (
                <>
                    <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
                        <KpiCard label="Win Rate" value={formatPercentFromRatio(metrics?.winRate)} />
                        <KpiCard label="Avg R" value={formatDecimal(metrics?.avgWinR ?? metrics?.avgR, 2)} />
                        <KpiCard label="Expectancy (R)" value={formatDecimal(metrics?.expectancyR, 3)} />
                        <KpiCard label="Profit Factor" value={formatDecimal(metrics?.profitFactor, 3)} />
                        <KpiCard label="Max Drawdown" value={formatPercentFromRatio(metrics?.maxDrawdownPct)} />
                        <KpiCard label="Avg Hold Time" value={formatDurationMinutes(metrics?.avgHoldMinutes)} />
                    </div>

                    <div className="mt-5 rounded-lg border border-slate-700 bg-slate-900/40 p-4">
                        <h3 className="text-sm font-semibold text-slate-200">Top 3 Failure Patterns</h3>
                        {topPatterns.length === 0 ? (
                            <p className="mt-2 text-sm text-slate-400">No failure patterns available yet.</p>
                        ) : (
                            <ul className="mt-2 space-y-2 text-sm text-slate-300">
                                {topPatterns.map((pattern) => (
                                    <li key={pattern.pattern} className="rounded border border-slate-700 bg-slate-950/40 p-2">
                                        <p>
                                            <span className="font-semibold text-slate-100">{pattern.pattern}</span> ({pattern.count})
                                        </p>
                                        <p className="text-xs text-slate-400">{pattern.hypothesis}</p>
                                    </li>
                                ))}
                            </ul>
                        )}
                    </div>

                    <div className="mt-5 grid grid-cols-1 gap-4 lg:grid-cols-2">
                        <div className="h-64 rounded-lg border border-slate-700 bg-slate-900/40 p-3">
                            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-400">
                                Close Reason Distribution
                            </h3>
                            <ResponsiveContainer width="100%" height="90%">
                                <BarChart data={closeReasonData}>
                                    <CartesianGrid stroke="#334155" strokeDasharray="3 3" />
                                    <XAxis dataKey="reason" stroke="#94a3b8" fontSize={11} />
                                    <YAxis stroke="#94a3b8" fontSize={11} allowDecimals={false} />
                                    <Tooltip
                                        contentStyle={{ backgroundColor: '#0f172a', borderColor: '#334155', borderRadius: '8px', fontSize: '12px' }}
                                    />
                                    <Bar dataKey="count" fill="#60a5fa" radius={[4, 4, 0, 0]} />
                                </BarChart>
                            </ResponsiveContainer>
                        </div>

                        <div className="h-64 rounded-lg border border-slate-700 bg-slate-900/40 p-3">
                            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-400">
                                Side Win Rate
                            </h3>
                            <ResponsiveContainer width="100%" height="90%">
                                <BarChart data={sideWinrateData}>
                                    <CartesianGrid stroke="#334155" strokeDasharray="3 3" />
                                    <XAxis dataKey="bucket" stroke="#94a3b8" fontSize={11} />
                                    <YAxis stroke="#94a3b8" fontSize={11} domain={[0, 100]} />
                                    <Tooltip
                                        formatter={(value: number | string | undefined) => {
                                            const parsed = typeof value === 'number' ? value : Number(value);
                                            return [`${Number.isFinite(parsed) ? parsed.toFixed(2) : '0.00'}%`, 'Win Rate'];
                                        }}
                                        contentStyle={{ backgroundColor: '#0f172a', borderColor: '#334155', borderRadius: '8px', fontSize: '12px' }}
                                    />
                                    <Bar dataKey="winRatePct" fill="#34d399" radius={[4, 4, 0, 0]} />
                                </BarChart>
                            </ResponsiveContainer>
                        </div>
                    </div>
                </>
            )}
        </section>
    );
}
