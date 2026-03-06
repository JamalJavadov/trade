import { motion } from 'framer-motion';

export function ProgressBarCard({
    evaluated,
    total,
    validCount,
    noTradeCount
}: {
    evaluated: number;
    total: number;
    validCount: number;
    noTradeCount: number;
}) {
    const progressPct = total > 0 ? Math.min(100, Math.round((evaluated / total) * 100)) : 0;

    return (
        <div className="bg-slate-800 border border-slate-700 rounded-xl p-4 shadow-sm flex flex-col gap-4">
            <div className="flex justify-between items-end">
                <div>
                    <h3 className="text-sm font-medium text-slate-400">Processing Pipeline</h3>
                    <p className="text-2xl font-bold text-white mt-1">
                        {evaluated} <span className="text-lg text-slate-500 font-normal">/ {total}</span>
                    </p>
                </div>
                <div className="flex gap-4 text-right">
                    <div className="flex flex-col text-sm border-r border-slate-700 pr-4">
                        <span className="text-emerald-400 font-bold">{validCount}</span>
                        <span className="text-slate-500 text-xs uppercase tracking-wider">Valid</span>
                    </div>
                    <div className="flex flex-col text-sm">
                        <span className="text-slate-300 font-bold">{noTradeCount}</span>
                        <span className="text-slate-500 text-xs uppercase tracking-wider">Skipped</span>
                    </div>
                </div>
            </div>

            <div className="h-3 w-full bg-slate-900 rounded-full overflow-hidden border border-slate-700/50">
                <motion.div
                    className="h-full bg-gradient-to-r from-blue-600 to-indigo-400 rounded-full"
                    initial={{ width: 0 }}
                    animate={{ width: `${progressPct}%` }}
                    transition={{ type: "spring", stiffness: 50, damping: 15 }}
                />
            </div>
        </div>
    );
}
