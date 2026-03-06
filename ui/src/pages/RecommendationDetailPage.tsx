import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, AlertTriangle, CheckCircle } from 'lucide-react';
import { getRecommendation, type RecommendationDTO } from '../api/client';
import { JsonBlock } from '../components/JsonBlock';
import { FeedbackForm } from '../components/FeedbackForm';
import { BinanceFillGuide } from '../components/BinanceFillGuide';
import { journalStore, type JournalItem } from '../store/journalStore';

export const RecommendationDetailPage: React.FC = () => {
    const navigate = useNavigate();
    const { id } = useParams<{ id: string }>();
    const [data, setData] = useState<RecommendationDTO | null>(null);
    const [loading, setLoading] = useState(true);
    const [journalEntry, setJournalEntry] = useState<JournalItem | null>(null);
    const [saved, setSaved] = useState(false);
    const [manualPlacementLocked, setManualPlacementLocked] = useState(true);
    const [manualPlacementLockReason, setManualPlacementLockReason] = useState(
        'Live MARK preflight pending. Refresh mark before placing.'
    );

    const syncJournalState = (recId: string) => {
        const items = journalStore.list();
        const item = items.find(i => i.id === recId) || null;
        setJournalEntry(item);
    };

    useEffect(() => {
        const fetchRec = async () => {
            if (!id) return;
            try {
                const res = await getRecommendation(id);
                setData(res);
                journalStore.markAsOpen(id);
                syncJournalState(id);
            } catch (e) {
                console.error(e);
            } finally {
                setLoading(false);
            }
        };
        fetchRec();
    }, [id]);

    const handleFeedbackSuccess = (result: 'WIN' | 'LOSS', pnlUsdt?: number, rMultiple?: number) => {
        if (id) {
            journalStore.updateFeedbackStatus(id, result, pnlUsdt, rMultiple);
            syncJournalState(id);
        }
        setSaved(true);
        setTimeout(() => setSaved(false), 3000);
    };

    const handleLockStateChange = (locked: boolean, reason: string) => {
        setManualPlacementLocked(locked);
        setManualPlacementLockReason(reason);
    };

    if (loading) {
        return <div className="p-8 text-center text-gray-400 animate-pulse">Loading execution details...</div>;
    }

    if (!data) {
        return (
            <div className="p-8 text-center text-gray-400">
                <p>No recommendation found.</p>
                <button onClick={() => navigate(-1)} className="mt-4 text-blue-400 hover:text-blue-300">
                    Go Back
                </button>
            </div>
        );
    }

    return (
        <div className="max-w-5xl mx-auto p-6">
            <button
                onClick={() => navigate(-1)}
                className="flex items-center text-gray-400 hover:text-white mb-6 transition-colors"
            >
                <ArrowLeft size={16} className="mr-2" /> Back to Dashboard
            </button>

            <div className="bg-gray-800 rounded-lg border border-gray-700 p-6 shadow-xl mb-6">
                <div className="flex justify-between items-start mb-6">
                    <div>
                        <h1 className="text-3xl font-black text-white tracking-widest">{data.symbol}</h1>
                        <p className="text-gray-400 mt-1">{data.rationaleText}</p>
                    </div>
                    <div className="text-right">
                        <span className={`px-4 py-2 rounded font-bold ${data.side === 'BUY' ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'
                            }`}>
                            {data.side}
                        </span>
                    </div>
                </div>

                <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
                    <div className="bg-gray-900 border border-gray-700 p-4 rounded text-center">
                        <p className="text-xs text-gray-500 uppercase tracking-widest mb-1">Leverage</p>
                        <p className="text-lg font-mono text-white">{data.leverageRecommendation}x</p>
                    </div>
                    <div className="bg-gray-900 border border-gray-700 p-4 rounded text-center">
                        <p className="text-xs text-gray-500 uppercase tracking-widest mb-1">Margin Mode</p>
                        <p className="text-lg font-mono text-white">{data.marginMode}</p>
                    </div>
                    <div className="bg-gray-900 border border-gray-700 p-4 rounded text-center">
                        <p className="text-xs text-gray-500 uppercase tracking-widest mb-1">Position</p>
                        <p className="text-lg font-mono text-white">{data.positionMode}</p>
                    </div>
                    <div className="bg-gray-900 border border-gray-700 p-4 rounded text-center">
                        <p className="text-xs text-gray-500 uppercase tracking-widest mb-1">Quantity</p>
                        <p className="text-lg font-mono text-yellow-400">{data.entryOrder?.quantity || 'N/A'}</p>
                    </div>
                </div>

                <div className="bg-yellow-900/30 border border-yellow-700/50 p-4 rounded mb-8 flex items-start">
                    <AlertTriangle className="text-yellow-500 mr-3 shrink-0 mt-1" size={20} />
                    <div>
                        <h4 className="text-yellow-400 font-bold mb-1">Manual Placement Required</h4>
                        <p className="text-yellow-200/70 text-sm">
                            The bot does NOT place automated orders. Use the JSON formats below to place these exact orders manually
                            in your Binance Futures terminal. Copying prevents fat-finger typing mistakes.
                        </p>
                    </div>
                </div>

                <h2 className="text-xl font-bold text-gray-200 mb-4 mt-8">API Order Payloads</h2>
                <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                    <JsonBlock
                        label="Entry (MARKET)"
                        data={data.entryOrder}
                        copyDisabled={manualPlacementLocked}
                        copyDisabledTitle={manualPlacementLockReason}
                        lockMessage={manualPlacementLocked ? manualPlacementLockReason : undefined}
                    />
                    <JsonBlock
                        label="Stop Loss (STOP_MARKET)"
                        data={data.slOrder}
                        copyDisabled={manualPlacementLocked}
                        copyDisabledTitle={manualPlacementLockReason}
                        lockMessage={manualPlacementLocked ? manualPlacementLockReason : undefined}
                    />
                    <JsonBlock
                        label="Take Profit 1 (MARKET)"
                        data={data.tpOrder}
                        copyDisabled={manualPlacementLocked}
                        copyDisabledTitle={manualPlacementLockReason}
                        lockMessage={manualPlacementLocked ? manualPlacementLockReason : undefined}
                    />
                </div>

                <BinanceFillGuide rec={data} onLockStateChange={handleLockStateChange} />

                {/* Feedback Section */}
                {saved && (
                    <div className="mt-6 bg-green-900/40 border border-green-600/50 p-4 rounded-lg flex items-center text-green-300">
                        <CheckCircle size={18} className="mr-2 shrink-0" />
                        Trade result saved successfully.
                    </div>
                )}
                {journalEntry && journalEntry.status === 'CLOSED' ? (
                    <div className="mt-8 bg-gray-900 border border-gray-700 p-6 rounded-lg shadow-xl">
                        <h3 className="text-lg font-bold text-white mb-4">Trade Result</h3>
                        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                            <div>
                                <p className="text-sm text-gray-500">Outcome</p>
                                <p className={`text-xl font-bold ${journalEntry.feedbackResult === 'WIN' ? 'text-green-500' : 'text-red-500'}`}>
                                    {journalEntry.feedbackResult}
                                </p>
                            </div>
                            <div>
                                <p className="text-sm text-gray-500">PnL (USDT)</p>
                                <p className="text-lg text-white font-mono">{journalEntry.feedbackPnl !== undefined ? journalEntry.feedbackPnl : 'N/A'}</p>
                            </div>
                            <div>
                                <p className="text-sm text-gray-500">R-Multiple</p>
                                <p className="text-lg text-white font-mono">{journalEntry.feedbackR !== undefined ? journalEntry.feedbackR : 'N/A'}</p>
                            </div>
                        </div>
                    </div>
                ) : (
                    id && <FeedbackForm recommendationId={id} onSuccess={handleFeedbackSuccess} />
                )}
            </div>
        </div>
    );
};
