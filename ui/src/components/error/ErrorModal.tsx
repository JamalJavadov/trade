import React from 'react';
import { X, Copy, AlertTriangle } from 'lucide-react';
import type { ErrorRecord } from '../../store/errorStore';
import { getErrorExplanation } from '../../utils/errorMap';

interface ErrorModalProps {
    error: ErrorRecord;
    isOpen: boolean;
    onClose: () => void;
}

export const ErrorModal: React.FC<ErrorModalProps> = ({ error, isOpen, onClose }) => {
    if (!isOpen) return null;

    const explainer = getErrorExplanation(error.errorCode);

    const handleCopy = () => {
        const report = {
            timestamp: error.timestamp,
            page: window.location.pathname,
            request: error.path,
            errorCode: error.errorCode,
            message: error.message,
            traceId: error.traceId,
            details: error.details
        };
        navigator.clipboard.writeText(JSON.stringify(report, null, 2));
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4 animate-in fade-in duration-200">
            <div className="bg-slate-900 border border-slate-700 rounded-xl shadow-2xl w-full max-w-2xl overflow-hidden flex flex-col max-h-[90vh]">

                {/* Header */}
                <div className="flex justify-between items-center p-4 border-b border-slate-800 bg-slate-800/50">
                    <div className="flex items-center gap-3">
                        <div className="p-2 bg-rose-500/20 rounded-lg">
                            <AlertTriangle size={20} className="text-rose-400" />
                        </div>
                        <div>
                            <h2 className="text-lg font-bold text-white leading-tight">System Error: {error.errorCode}</h2>
                            <p className="text-xs text-slate-400 font-mono mt-0.5">TraceID: {error.traceId ?? '(no trace id)'}</p>
                        </div>
                    </div>
                    <button
                        onClick={onClose}
                        className="p-2 text-slate-400 hover:text-white hover:bg-slate-700 rounded-lg transition-colors"
                    >
                        <X size={20} />
                    </button>
                </div>

                {/* Body */}
                <div className="p-6 overflow-y-auto space-y-6">

                    {/* Explainer Block */}
                    <div className="bg-slate-800 rounded-lg border border-slate-700 p-4">
                        <h3 className="text-sm font-bold text-slate-300 uppercase tracking-wider mb-3">Root Cause Analysis</h3>
                        <div className="space-y-3">
                            <div>
                                <span className="text-xs font-semibold text-rose-400 block mb-1">Why it happened:</span>
                                <p className="text-sm text-slate-300">{explainer.why}</p>
                            </div>
                            <div>
                                <span className="text-xs font-semibold text-emerald-400 block mb-1">How to fix it:</span>
                                <p className="text-sm text-slate-300">{explainer.fix}</p>
                            </div>
                        </div>
                    </div>

                    {/* Technical Details */}
                    <div>
                        <h3 className="text-sm font-bold text-slate-300 uppercase tracking-wider mb-3">Technical Diagnostics</h3>
                        <div className="bg-slate-950 rounded-lg p-4 font-mono text-xs overflow-x-auto border border-slate-800">
                            <div className="grid grid-cols-[100px_1fr] gap-2 text-slate-400 mb-4">
                                <span className="opacity-60">Timestamp:</span><span>{new Date(error.timestamp).toLocaleString()}</span>
                                <span className="opacity-60">Request Path:</span><span className="text-amber-300/80">{error.path}</span>
                                <span className="opacity-60">Message:</span><span className="text-rose-300/80">{error.message}</span>
                            </div>

                            {error.details && (
                                <div className="mt-4 pt-4 border-t border-slate-800/50">
                                    <span className="opacity-60 block mb-2">Payload Details:</span>
                                    <pre className="text-indigo-300/80 m-0">
                                        {typeof error.details === 'string'
                                            ? error.details
                                            : JSON.stringify(error.details, null, 2)}
                                    </pre>
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                {/* Footer */}
                <div className="p-4 border-t border-slate-800 bg-slate-900 flex justify-between items-center">
                    <button
                        onClick={handleCopy}
                        className="flex items-center gap-2 px-4 py-2 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-lg text-sm font-medium transition-colors border border-slate-700"
                    >
                        <Copy size={16} />
                        Copy Error Report
                    </button>
                    <button
                        onClick={onClose}
                        className="px-5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-sm font-bold transition-colors"
                    >
                        Acknowledge
                    </button>
                </div>
            </div>
        </div>
    );
};
