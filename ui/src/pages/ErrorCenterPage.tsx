import React from 'react';
import { useErrorStore } from '../store/errorStore';
import type { ErrorRecord } from '../store/errorStore';
import { getErrorExplanation } from '../utils/errorMap';
import { Trash2, AlertOctagon, Terminal, Copy } from 'lucide-react';

export const ErrorCenterPage: React.FC = () => {
    const { errors, clearErrors } = useErrorStore();

    const handleExport = () => {
        const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(errors, null, 2));
        const downloadAnchorNode = document.createElement('a');
        downloadAnchorNode.setAttribute("href", dataStr);
        downloadAnchorNode.setAttribute("download", "tradebot_errors.json");
        document.body.appendChild(downloadAnchorNode);
        downloadAnchorNode.click();
        downloadAnchorNode.remove();
    };

    const copyError = (err: ErrorRecord) => {
        navigator.clipboard.writeText(JSON.stringify(err, null, 2));
    };

    const copyTrace = (traceId: string | null) => {
        if (!traceId) return;
        navigator.clipboard.writeText(traceId);
    };

    return (
        <div className="space-y-6">
            <div className="flex justify-between items-center">
                <div>
                    <h1 className="text-3xl font-bold text-white flex items-center gap-3">
                        <AlertOctagon className="text-rose-500" size={32} />
                        Error Center
                    </h1>
                    <p className="text-slate-400 mt-2">Historical log of API and Stream drops (Max 50).</p>
                </div>

                <div className="flex items-center gap-3">
                    <button
                        onClick={handleExport}
                        disabled={errors.length === 0}
                        className="px-4 py-2 bg-slate-800 hover:bg-slate-700 disabled:opacity-50 text-slate-300 rounded-lg text-sm font-medium transition-colors border border-slate-700 flex items-center gap-2"
                    >
                        <Terminal size={16} />
                        Export JSON
                    </button>
                    <button
                        onClick={clearErrors}
                        disabled={errors.length === 0}
                        className="px-4 py-2 bg-rose-500/10 hover:bg-rose-500/20 disabled:opacity-50 text-rose-400 rounded-lg text-sm font-medium transition-colors border border-rose-500/20 flex items-center gap-2"
                    >
                        <Trash2 size={16} />
                        Clear All
                    </button>
                </div>
            </div>

            {errors.length === 0 ? (
                <div className="bg-slate-900 border border-slate-800 rounded-xl p-12 text-center">
                    <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-slate-800 mb-4">
                        <AlertOctagon className="text-slate-600" size={32} />
                    </div>
                    <h3 className="text-lg font-medium text-slate-300">No active errors</h3>
                    <p className="text-slate-500 mt-1 max-w-sm mx-auto">The system is running smoothly. Recorded errors will appear here.</p>
                </div>
            ) : (
                <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-xl">
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm whitespace-nowrap">
                            <thead className="bg-slate-950/50 text-slate-400 border-b border-slate-800 text-xs uppercase tracking-wider">
                                <tr>
                                    <th className="px-6 py-4 font-medium">Time</th>
                                    <th className="px-6 py-4 font-medium">ErrorCode</th>
                                    <th className="px-6 py-4 font-medium">Message & Path</th>
                                    <th className="px-6 py-4 font-medium text-right">Actions</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-800/50">
                                {errors.map((err) => (
                                    <tr key={err.id} className="hover:bg-slate-800/20 transition-colors group">
                                        <td className="px-6 py-4 align-top">
                                            <div className="text-slate-300">{new Date(err.timestamp).toLocaleTimeString()}</div>
                                            <div className="text-xs text-slate-500 mt-1">{new Date(err.timestamp).toLocaleDateString()}</div>
                                        </td>
                                        <td className="px-6 py-4 align-top">
                                            <span className="inline-flex px-2.5 py-1 rounded bg-rose-500/10 text-rose-400 font-mono text-xs font-bold border border-rose-500/20">
                                                {err.errorCode}
                                            </span>
                                            {!err.resolved && (
                                                <span className="ml-2 inline-flex w-2 h-2 rounded-full bg-rose-500 animate-pulse" title="Unresolved" />
                                            )}
                                        </td>
                                        <td className="px-6 py-4 align-top w-full max-w-xl">
                                            <div className="text-slate-200 font-medium truncate">{err.message}</div>
                                            <div className="mt-1 flex gap-2 overflow-x-auto flex-wrap text-xs">
                                                <span className="text-slate-500 font-mono truncate">{err.path}</span>
                                                <span className="text-slate-600 font-mono">Trace: {err.traceId ?? '(no trace id)'}</span>
                                            </div>
                                            <div className="mt-3 text-xs text-slate-400 bg-slate-950/50 p-2 rounded border border-slate-800 truncate">
                                                <strong className="opacity-70">Hint:</strong> {getErrorExplanation(err.errorCode).why}
                                            </div>
                                        </td>
                                        <td className="px-6 py-4 align-top text-right">
                                            <div className="opacity-0 group-hover:opacity-100 flex items-center justify-end gap-2 transition-all">
                                                <button
                                                    onClick={() => copyTrace(err.traceId)}
                                                    disabled={!err.traceId}
                                                    className="p-2 text-slate-400 hover:text-white hover:bg-slate-700 rounded transition-all disabled:opacity-40 disabled:cursor-not-allowed"
                                                    title={err.traceId ? 'Copy Trace ID' : 'Trace ID unavailable'}
                                                >
                                                    <span className="text-[11px] font-mono">Trace</span>
                                                </button>
                                                <button
                                                    onClick={() => copyError(err)}
                                                    className="p-2 text-slate-400 hover:text-white hover:bg-slate-700 rounded transition-all"
                                                    title="Copy Raw JSON"
                                                >
                                                    <Copy size={16} />
                                                </button>
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}
        </div>
    );
};
