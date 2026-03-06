import React from 'react';
import { AlertTriangle, Lock } from 'lucide-react';

interface ConfirmModalProps {
    isOpen: boolean;
    onConfirm: () => void;
    onCancel: () => void;
    loading: boolean;
}

export const ConfirmModal: React.FC<ConfirmModalProps> = ({ isOpen, onConfirm, onCancel, loading }) => {
    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
            <div className="bg-gray-800 border border-gray-700 rounded-xl shadow-2xl max-w-lg w-full overflow-hidden">

                <div className="bg-yellow-900/40 border-b border-yellow-700/50 p-6 flex flex-col items-center text-center">
                    <AlertTriangle className="text-yellow-500 mb-3" size={48} />
                    <h2 className="text-2xl font-bold text-white">Activate New Strategy Config?</h2>
                    <p className="text-yellow-200 mt-2 leading-relaxed">
                        Accepting this batch will immediately update the core trading heuristics for all future scans.
                    </p>
                </div>

                <div className="p-6">
                    <h3 className="text-sm font-bold text-gray-400 uppercase tracking-widest mb-3 flex items-center">
                        <Lock size={14} className="mr-2" />
                        Hard Invariants Enforced
                    </h3>
                    <ul className="space-y-2 text-sm text-gray-300">
                        <li className="flex items-center"><span className="w-1.5 h-1.5 bg-gray-500 rounded-full mr-2"></span>Minimum RR limit locked to <strong className="ml-1 font-mono text-white">{`>= 2.0`}</strong></li>
                        <li className="flex items-center"><span className="w-1.5 h-1.5 bg-gray-500 rounded-full mr-2"></span>Max Equity % locked to <strong className="ml-1 font-mono text-white">{`<= 1.0%`}</strong></li>
                        <li className="flex items-center"><span className="w-1.5 h-1.5 bg-gray-500 rounded-full mr-2"></span>Execution TF <strong className="mx-1 font-mono text-white">15m</strong> & Bias TF <strong className="mx-1 font-mono text-white">1h</strong></li>
                        <li className="flex items-center"><span className="w-1.5 h-1.5 bg-gray-500 rounded-full mr-2"></span>Fractal Period strictly locked to <strong className="ml-1 font-mono text-white">5</strong></li>
                    </ul>

                    <div className="mt-8 flex space-x-4">
                        <button
                            disabled={loading}
                            onClick={onCancel}
                            className="flex-1 py-3 px-4 bg-gray-700 hover:bg-gray-600 border border-gray-600 rounded font-medium text-white transition-colors"
                        >
                            Cancel
                        </button>
                        <button
                            disabled={loading}
                            onClick={onConfirm}
                            className="flex-1 py-3 px-4 bg-yellow-600 hover:bg-yellow-500 border border-yellow-500 rounded font-bold text-gray-900 transition-colors"
                        >
                            {loading ? 'Activating...' : 'Yes, Apply Config'}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
};
