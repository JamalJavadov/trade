import {
    BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
    ScatterChart, Scatter, ZAxis, Cell,
    ReferenceLine
} from 'recharts';
import type { ScanChartsDTO } from '../../types/scan';

export function ChartsGrid({ charts, loading }: { charts: ScanChartsDTO | null, loading?: boolean }) {
    if (!charts) {
        if (loading) {
            return (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4 animate-pulse">
                    <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-4 h-64"></div>
                    <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-4 h-64"></div>
                    <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-4 h-64"></div>
                    <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-4 h-64"></div>
                </div>
            );
        }
        return null;
    }

    // Colors mapping for bias/reasons
    const COLORS = {
        VALID: '#34d399', // emerald-400
        UPTREND: '#3b82f6', // blue-500 
        DOWNTREND: '#f43f5e', // rose-500
        DEFAULT: '#64748b' // slate-500
    };

    const rrData = (charts.rrHist ?? []).map(b => {
        const start = Number(b?.bucketStart);
        const end = Number(b?.bucketEnd);
        return {
            name: Number.isFinite(start) && Number.isFinite(end)
                ? `${start.toFixed(1)} - ${end.toFixed(1)}`
                : 'N/A',
            count: Number(b?.count ?? 0)
        };
    });

    const confData = (charts.confHist ?? []).map(b => {
        const start = Number(b?.bucketStart);
        const end = Number(b?.bucketEnd);
        return {
            name: Number.isFinite(start) && Number.isFinite(end)
                ? `${start.toFixed(2)} - ${end.toFixed(2)}`
                : 'N/A',
            count: Number(b?.count ?? 0)
        };
    });

    const skipData = Object.entries(charts.skipReasonBreakdown ?? {})
        .sort((a, b) => b[1] - a[1]) // sort descending
        .map(([code, count]) => ({ code, count }));

    const scatterData = Array.isArray(charts.scatterPoints)
        ? charts.scatterPoints.filter(p =>
            Number.isFinite(Number(p.confidence)) &&
            Number.isFinite(Number(p.rrTp1)) &&
            Number.isFinite(Number(p.finalScore)))
        : [];

    return (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">

            {/* Risk/Reward Histogram */}
            <div className="bg-slate-800 border border-slate-700 rounded-xl p-4 h-64 shadow-md">
                <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2">RR Distribution (Valid Only)</h3>
                <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={rrData} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
                        <XAxis dataKey="name" stroke="#64748b" fontSize={10} tickLine={false} axisLine={false} />
                        <YAxis stroke="#64748b" fontSize={10} tickLine={false} axisLine={false} allowDecimals={false} />
                        <Tooltip
                            contentStyle={{ backgroundColor: '#1e293b', borderColor: '#334155', borderRadius: '8px', fontSize: '12px' }}
                            itemStyle={{ color: '#e2e8f0' }}
                            cursor={{ fill: '#334155', opacity: 0.4 }}
                        />
                        <Bar dataKey="count" fill="#38bdf8" radius={[4, 4, 0, 0]} />
                    </BarChart>
                </ResponsiveContainer>
            </div>

            {/* Scatter Plot: RR vs Confidence */}
            <div className="bg-slate-800 border border-slate-700 rounded-xl p-4 h-64 shadow-md">
                <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2">Quality Scatter (Confidence vs RR)</h3>
                <ResponsiveContainer width="100%" height="100%">
                    <ScatterChart margin={{ top: 5, right: 10, left: -20, bottom: 10 }}>
                        <XAxis type="number" dataKey="confidence" name="Confidence" domain={['auto', 'auto']} stroke="#64748b" fontSize={10} tickLine={false} axisLine={false} />
                        <YAxis type="number" dataKey="rrTp1" name="RR (TP1)" domain={[2.0, 'auto']} stroke="#64748b" fontSize={10} tickLine={false} axisLine={false} />
                        <ZAxis type="number" dataKey="finalScore" range={[20, 100]} name="Score" />
                        <Tooltip
                            cursor={{ strokeDasharray: '3 3', stroke: '#475569' }}
                            contentStyle={{ backgroundColor: '#1e293b', borderColor: '#334155', borderRadius: '8px', fontSize: '12px' }}
                            formatter={(value: number | undefined, name: string | undefined) => [Number(value || 0).toFixed(2), name === 'count' ? 'Freq' : (name || '')]}
                            labelFormatter={() => ''}
                        />
                        <ReferenceLine y={2.5} stroke="#ec4899" strokeDasharray="3 3" opacity={0.5} />
                        <Scatter data={scatterData} fill={COLORS.VALID} fillOpacity={0.6} />
                    </ScatterChart>
                </ResponsiveContainer>
            </div>

            {/* Skip Reasons */}
            <div className="bg-slate-800 border border-slate-700 rounded-xl p-4 h-64 shadow-md">
                <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2">Rejection Reasons</h3>
                <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={skipData} layout="vertical" margin={{ top: 5, right: 10, left: 20, bottom: 0 }}>
                        <XAxis type="number" stroke="#64748b" fontSize={10} tickLine={false} axisLine={false} hide />
                        <YAxis type="category" dataKey="code" stroke="#cbd5e1" fontSize={10} tickLine={false} axisLine={false} width={100} />
                        <Tooltip
                            contentStyle={{ backgroundColor: '#1e293b', borderColor: '#334155', borderRadius: '8px', fontSize: '12px' }}
                            cursor={{ fill: '#334155', opacity: 0.4 }}
                        />
                        <Bar dataKey="count" radius={[0, 4, 4, 0]}>
                            {skipData.map((entry, index) => (
                                <Cell key={`cell-${index}`} fill={
                                    entry.code === 'NO_BIAS' ? '#94a3b8' :
                                        entry.code === 'DATA_ERROR' ? '#f43f5e' :
                                            entry.code === 'RR_TOO_LOW' ? '#f59e0b' : '#64748b'
                                } />
                            ))}
                        </Bar>
                    </BarChart>
                </ResponsiveContainer>
            </div>

            {/* Confidence Histogram */}
            <div className="bg-slate-800 border border-slate-700 rounded-xl p-4 h-64 shadow-md">
                <h3 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2">Confidence Score Dist.</h3>
                <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={confData} margin={{ top: 5, right: 10, left: -20, bottom: 0 }}>
                        <XAxis dataKey="name" stroke="#64748b" fontSize={10} tickLine={false} axisLine={false} />
                        <YAxis stroke="#64748b" fontSize={10} tickLine={false} axisLine={false} allowDecimals={false} />
                        <Tooltip
                            contentStyle={{ backgroundColor: '#1e293b', borderColor: '#334155', borderRadius: '8px', fontSize: '12px' }}
                            itemStyle={{ color: '#e2e8f0' }}
                            cursor={{ fill: '#334155', opacity: 0.4 }}
                        />
                        <Bar dataKey="count" fill="#818cf8" radius={[4, 4, 0, 0]} />
                    </BarChart>
                </ResponsiveContainer>
            </div>

        </div>
    );
}
