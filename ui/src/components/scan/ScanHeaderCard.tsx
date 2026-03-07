import type { ScanSummaryDTO } from '../../types/scan';
import { Clock, Activity, AlertTriangle, CheckCircle2 } from 'lucide-react';
import { ConnectionBadge } from './ConnectionBadge';

export function ScanHeaderCard({
    summary,
    statusOverride,
    failureReason,
    sseConnected,
    reconnecting,
    polling,
    lastUpdatedAt
}: {
    summary: ScanSummaryDTO | null;
    statusOverride?: string;
    failureReason?: string | null;
    sseConnected: boolean;
    reconnecting: boolean;
    polling: boolean;
    lastUpdatedAt?: string;
}) {
    const status = statusOverride || summary?.status || 'UNKNOWN';
    const normalizedFailureReason = failureReason && failureReason.trim().length > 0
        ? failureReason.trim()
        : null;

    const getStatusColor = () => {
        switch (status) {
            case 'STARTED': return 'text-blue-400';
            case 'FINISHED': return 'text-emerald-400';
            case 'FAILED': return 'text-rose-400';
            default: return 'text-slate-400';
        }
    };

    const StatusIcon = status === 'STARTED' ? Activity
        : status === 'FINISHED' ? CheckCircle2
            : status === 'FAILED' ? AlertTriangle : Clock;

    return (
        <div className="bg-slate-800 border border-slate-700 rounded-xl p-4 flex flex-col md:flex-row justify-between items-start md:items-center gap-4 shadow-lg">
            <div className="flex flex-col gap-1">
                <div className="flex items-center gap-2">
                    <h2 className="text-lg font-semibold text-white">Live Scan Pipeline</h2>
                    <ConnectionBadge connected={sseConnected} reconnecting={reconnecting} polling={polling} />
                </div>
                {summary && (
                    <div className="flex items-center gap-3">
                        <p className="text-sm text-slate-400 font-mono">
                            Run ID: {summary.id.substring(0, 8)}...
                        </p>
                        <p className="text-xs text-slate-500">
                            Eligible {summary.eligibleCount ?? 0} / Blocked {summary.blockedCount ?? 0} / Conflicts {summary.conflictCount ?? 0}
                        </p>
                        {lastUpdatedAt && (
                            <span className="text-xs text-slate-500">
                                Updated {lastUpdatedAt}
                            </span>
                        )}
                    </div>
                )}
                {status === 'FAILED' && normalizedFailureReason && (
                    <p className="text-xs text-rose-300/90 max-w-3xl">
                        Reason: {normalizedFailureReason}
                    </p>
                )}
            </div>

            <div className="flex items-center gap-6">
                {summary && (
                    <div className="flex flex-col text-right">
                        <span className="text-xs text-slate-500 uppercase tracking-wider">Started</span>
                        <span className="text-sm text-slate-300">
                            {new Date(summary.startedAt).toLocaleTimeString()}
                        </span>
                    </div>
                )}
                <div className="flex items-center gap-2 bg-slate-900/50 px-4 py-2 rounded-lg border border-slate-700/50">
                    <StatusIcon className={`w-5 h-5 ${getStatusColor()}`} />
                    <span className={`font-semibold tracking-wide ${getStatusColor()}`}>
                        {status}
                    </span>
                </div>
            </div>
        </div>
    );
}
