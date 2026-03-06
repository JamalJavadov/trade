import { useState } from 'react';
import { AlertTriangle } from 'lucide-react';

interface DemoResetModalProps {
    isOpen: boolean;
    loading: boolean;
    onCancel: () => void;
    onConfirm: () => void;
}

export function DemoResetModal({ isOpen, loading, onCancel, onConfirm }: DemoResetModalProps) {
    const [inputValue, setInputValue] = useState('');

    if (!isOpen) {
        return null;
    }

    const canConfirm = inputValue === 'RESET' && !loading;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
            <div className="w-full max-w-lg rounded-xl border border-rose-700/50 bg-slate-900 shadow-2xl overflow-hidden">
                <div className="border-b border-rose-700/40 bg-rose-900/30 p-5">
                    <h2 className="text-xl font-bold text-rose-100 flex items-center gap-2">
                        <AlertTriangle size={20} />
                        Reset Demo Trading Data
                    </h2>
                    <p className="mt-2 text-sm text-rose-200/90">
                        This will clear demo trade history, analytics snapshots, AI batches, and reset the demo account.
                    </p>
                </div>

                <div className="p-5 space-y-4">
                    <p className="text-sm text-slate-300">
                        Type <span className="font-mono font-bold text-white">RESET</span> to confirm this destructive action.
                    </p>

                    <input
                        value={inputValue}
                        onChange={(event) => setInputValue(event.target.value)}
                        placeholder="Type RESET"
                        className="w-full rounded-lg border border-slate-700 bg-slate-800 px-3 py-2 text-sm text-white focus:border-rose-500 focus:outline-none"
                    />

                    <div className="flex gap-3 pt-2">
                        <button
                            type="button"
                            onClick={() => {
                                setInputValue('');
                                onCancel();
                            }}
                            disabled={loading}
                            className="flex-1 rounded-lg border border-slate-600 bg-slate-700 px-4 py-2 text-sm font-medium text-white hover:bg-slate-600 disabled:opacity-60"
                        >
                            Cancel
                        </button>
                        <button
                            type="button"
                            onClick={onConfirm}
                            disabled={!canConfirm}
                            className="flex-1 rounded-lg border border-rose-700 bg-rose-700/80 px-4 py-2 text-sm font-bold text-white hover:bg-rose-600 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            {loading ? 'Resetting...' : 'Confirm Reset'}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
