import { Line, LineChart, ResponsiveContainer, Tooltip } from 'recharts';
import type { DemoStatusResponse } from '../../api/demoApi';
import { formatDateTime, formatMoney, formatPercentDirect, formatSignedMoney, formatSignedR } from './demoFormat';

interface EquityPoint {
    at: string;
    equity: number;
}

interface DemoAccountCardProps {
    status: DemoStatusResponse | null;
    equityPoints: EquityPoint[];
    lastUpdatedAt: string | null;
}

function MetaRow({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex items-center justify-between border-b border-slate-700/60 py-2 text-sm last:border-b-0">
            <span className="text-slate-400">{label}</span>
            <span className="font-medium text-slate-100">{value}</span>
        </div>
    );
}

export function DemoAccountCard({ status, equityPoints, lastUpdatedAt }: DemoAccountCardProps) {
    return (
        <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
            <h2 className="text-lg font-semibold text-white">Demo Account</h2>

            <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
                <div className="rounded-lg border border-slate-700 bg-slate-900/40 p-3">
                    <p className="text-xs uppercase tracking-wider text-slate-400">Starting Balance</p>
                    <p className="mt-1 text-lg font-semibold text-slate-100">N/A</p>
                </div>
                <div className="rounded-lg border border-slate-700 bg-slate-900/40 p-3">
                    <p className="text-xs uppercase tracking-wider text-slate-400">Balance</p>
                    <p className="mt-1 text-lg font-semibold text-slate-100">{formatMoney(status?.account?.balanceUsdt)}</p>
                </div>
                <div className="rounded-lg border border-slate-700 bg-slate-900/40 p-3">
                    <p className="text-xs uppercase tracking-wider text-slate-400">Equity</p>
                    <p className="mt-1 text-lg font-semibold text-slate-100">{formatMoney(status?.account?.equityUsdt)}</p>
                </div>
            </div>

            {equityPoints.length > 1 && (
                <div className="mt-4 h-24 rounded-lg border border-slate-700 bg-slate-900/40 p-2">
                    <ResponsiveContainer width="100%" height="100%">
                        <LineChart data={equityPoints}>
                            <Tooltip
                                contentStyle={{ backgroundColor: '#0f172a', borderColor: '#334155', borderRadius: '8px', fontSize: '12px' }}
                                formatter={(value: number | string | undefined) => [formatMoney(value ?? null), 'Equity']}
                                labelFormatter={(label: unknown) =>
                                    formatDateTime(typeof label === 'string' ? label : String(label ?? ''))
                                }
                            />
                            <Line type="monotone" dataKey="equity" stroke="#60a5fa" strokeWidth={2} dot={false} />
                        </LineChart>
                    </ResponsiveContainer>
                </div>
            )}

            <div className="mt-4 rounded-lg border border-slate-700 bg-slate-900/30 px-3">
                <MetaRow label="Open Positions" value={`${status?.openPositionsCount ?? 0}`} />
                <MetaRow label="Last Demo Run" value={status?.lastDemoRunStatus ?? 'N/A'} />
                <MetaRow
                    label="Last Closed Trade"
                    value={status?.lastClosedTrade ? `${status.lastClosedTrade.symbol} (${formatSignedMoney(status.lastClosedTrade.pnlUsdt)})` : 'N/A'}
                />
                <MetaRow
                    label="Last Trade R"
                    value={status?.lastDemoTradeSummary ? formatSignedR(status.lastDemoTradeSummary.rMultiple) : 'N/A'}
                />
                <MetaRow
                    label="Win Rate"
                    value={status?.winRate !== null && status?.winRate !== undefined ? formatPercentDirect(status.winRate) : 'N/A'}
                />
                <MetaRow label="Last Updated" value={formatDateTime(lastUpdatedAt)} />
            </div>
        </section>
    );
}
