import { ShieldAlert } from 'lucide-react';

interface DemoAcceptModalProps {
    isOpen: boolean;
    loading: boolean;
    onCancel: () => void;
    onConfirm: () => void;
}

export function DemoAcceptModal({ isOpen, loading, onCancel, onConfirm }: DemoAcceptModalProps) {
    if (!isOpen) {
        return null;
    }

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
            <div className="w-full max-w-xl rounded-xl border border-blue-700/50 bg-slate-900 shadow-2xl overflow-hidden">
                <div className="border-b border-blue-700/40 bg-blue-900/20 p-5">
                    <h2 className="text-xl font-bold text-blue-100 flex items-center gap-2">
                        <ShieldAlert size={20} />
                        Accept Demo AI Suggestions
                    </h2>
                </div>

                <div className="space-y-4 p-5 text-sm text-slate-300">
                    <p>
                        Accepting will activate a new DEMO config version (demo-only). Locked invariants remain enforced
                        (minRR&gt;=2, TF locked, risk cap).
                    </p>

                    <div className="rounded-lg border border-slate-700 bg-slate-800 p-3 text-xs text-slate-300">
                        Paper Trading / Learning Mode only. Live scan and live AI flows are not modified.
                    </div>

                    <div className="flex gap-3 pt-2">
                        <button
                            type="button"
                            onClick={onCancel}
                            disabled={loading}
                            className="flex-1 rounded-lg border border-slate-600 bg-slate-700 px-4 py-2 text-sm font-medium text-white hover:bg-slate-600 disabled:opacity-60"
                        >
                            Cancel
                        </button>
                        <button
                            type="button"
                            onClick={onConfirm}
                            disabled={loading}
                            className="flex-1 rounded-lg border border-blue-600 bg-blue-600 px-4 py-2 text-sm font-bold text-white hover:bg-blue-500 disabled:opacity-60"
                        >
                            {loading ? 'Accepting...' : 'Accept & Activate'}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
