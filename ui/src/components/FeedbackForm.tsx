import React, { useState } from 'react';
import { type FeedbackRequest, submitFeedback } from '../api/client';
import { CheckCircle, AlertCircle, XCircle } from 'lucide-react';

interface FeedbackFormProps {
    recommendationId: string;
    onSuccess: (result: 'WIN' | 'LOSS', pnlUsdt?: number, rMultiple?: number) => void;
}

export const FeedbackForm: React.FC<FeedbackFormProps> = ({ recommendationId, onSuccess }) => {
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const [result, setResult] = useState<'WIN' | 'LOSS' | ''>('');
    const [pnlUsdt, setPnlUsdt] = useState('');
    const [rMultiple, setRMultiple] = useState('');
    const [notes, setNotes] = useState('');

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!result) {
            setError("Please select WIN or LOSS.");
            return;
        }

        setLoading(true);
        setError(null);

        const parsedPnl = pnlUsdt ? parseFloat(pnlUsdt) : undefined;
        const parsedR = rMultiple ? parseFloat(rMultiple) : undefined;

        const payload: FeedbackRequest = {
            userLabel: result,
            pnlUsdt: parsedPnl,
            rMultiple: parsedR,
            notes: notes || undefined
        };

        try {
            await submitFeedback(recommendationId, payload);
            onSuccess(result as 'WIN' | 'LOSS', parsedPnl, parsedR);
        } catch (err: any) {
            setError(err.message || "Failed to submit feedback");
            setLoading(false);
        }
    };

    return (
        <div className="bg-gray-800 p-6 rounded-lg border border-gray-700 shadow-xl mt-6">
            <h3 className="text-xl font-bold text-white mb-4 border-b border-gray-700 pb-2">Record Trade Result</h3>

            {error && (
                <div className="bg-red-900/50 border-l-4 border-red-500 p-3 mb-4 rounded flex items-center text-red-200 text-sm">
                    <AlertCircle size={16} className="mr-2 shrink-0" />
                    {error}
                </div>
            )}

            <form onSubmit={handleSubmit} className="space-y-4">

                <div className="flex space-x-4">
                    <button
                        type="button"
                        onClick={() => setResult('WIN')}
                        className={`flex-1 py-3 rounded-lg flex justify-center items-center font-bold transition-colors ${result === 'WIN'
                            ? 'bg-green-600 text-white shadow-lg border border-green-500'
                            : 'bg-gray-900 border border-gray-700 text-gray-400 hover:bg-gray-700'
                            }`}
                    >
                        <CheckCircle size={18} className="mr-2" /> WIN
                    </button>
                    <button
                        type="button"
                        onClick={() => setResult('LOSS')}
                        className={`flex-1 py-3 rounded-lg flex justify-center items-center font-bold transition-colors ${result === 'LOSS'
                            ? 'bg-red-600 text-white shadow-lg border border-red-500'
                            : 'bg-gray-900 border border-gray-700 text-gray-400 hover:bg-gray-700'
                            }`}
                    >
                        <XCircle size={18} className="mr-2" /> LOSS
                    </button>
                </div>

                <div className="grid grid-cols-2 gap-4">
                    <div>
                        <label className="block text-sm font-medium text-gray-400 mb-1">PnL (USDT) <span className="text-gray-600 font-normal">(optional)</span></label>
                        <input
                            type="number"
                            step="0.01"
                            value={pnlUsdt}
                            onChange={e => setPnlUsdt(e.target.value)}
                            placeholder="e.g. 50.25 or -20.50"
                            className="w-full bg-gray-900 border border-gray-700 rounded p-2 text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                        />
                    </div>
                    <div>
                        <label className="block text-sm font-medium text-gray-400 mb-1">Actual R-Multiple <span className="text-gray-600 font-normal">(optional)</span></label>
                        <input
                            type="number"
                            step="0.01"
                            value={rMultiple}
                            onChange={e => setRMultiple(e.target.value)}
                            placeholder="e.g. 2.5 or -1.0"
                            className="w-full bg-gray-900 border border-gray-700 rounded p-2 text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                        />
                    </div>
                </div>

                <div>
                    <label className="block text-sm font-medium text-gray-400 mb-1">Journal Notes <span className="text-gray-600 font-normal">(optional)</span></label>
                    <textarea
                        rows={3}
                        value={notes}
                        onChange={e => setNotes(e.target.value)}
                        placeholder="What went well? Did you follow the plan?"
                        className="w-full bg-gray-900 border border-gray-700 rounded p-2 text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none resize-y"
                        maxLength={500}
                    ></textarea>
                </div>

                <button
                    type="submit"
                    disabled={loading || !result}
                    className={`w-full py-3 rounded-lg font-bold transition-colors ${loading || !result
                        ? 'bg-blue-600/50 text-gray-300 cursor-not-allowed'
                        : 'bg-blue-600 text-white hover:bg-blue-500 shadow-lg'
                        }`}
                >
                    {loading ? 'Submitting...' : 'Mark Closed & Save'}
                </button>

            </form>
        </div>
    );
};
