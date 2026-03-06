import React, { useEffect, useState } from 'react';
import { getLatestAiSuggestions, acceptAiBatch, rejectAiBatch, type AiSuggestionLatestResponse } from '../api/client';
import { AiStatusCard } from '../components/AiStatusCard';
import { AiBatchCard } from '../components/AiBatchCard';
import { ConfirmModal } from '../components/ConfirmModal';
import { Banner } from '../components/Banner';
import { Sparkles, RefreshCcw, Info } from 'lucide-react';
import { usePermissions } from '../hooks/usePermissions';
import { disabledByPermissionTooltip } from '../utils/permissionUi';

export const AiPage: React.FC = () => {
    const { can } = usePermissions();
    const [data, setData] = useState<AiSuggestionLatestResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const [actionLoading, setActionLoading] = useState(false);
    const [showConfirmModal, setShowConfirmModal] = useState(false);
    const [successMsg, setSuccessMsg] = useState<string | null>(null);
    const canAcceptReject = can('ai.suggestions.accept_reject');
    const aiActionDisabledTooltip = disabledByPermissionTooltip('ai.suggestions.accept_reject', canAcceptReject);

    const fetchData = async () => {
        setLoading(true);
        setError(null);
        try {
            const res = await getLatestAiSuggestions();
            setData(res);
        } catch (err: any) {
            setError(err.message || "Failed to load AI suggestions");
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, []);

    const handleAcceptClick = () => {
        if (!canAcceptReject) {
            return;
        }
        setShowConfirmModal(true);
    };

    const handleConfirmAccept = async () => {
        if (!canAcceptReject) {
            return;
        }
        if (!data?.batch) return;
        setActionLoading(true);
        setError(null);
        try {
            await acceptAiBatch(data.batch.id);
            setSuccessMsg(`Successfully activated new Strategy Config based on Batch #${data.batch.id}`);
            setShowConfirmModal(false);
            fetchData();
        } catch (err: any) {
            setError(err.message || "Failed to apply AI modifications");
        } finally {
            setActionLoading(false);
        }
    };

    const handleReject = async () => {
        if (!canAcceptReject) {
            return;
        }
        if (!data?.batch) return;
        setActionLoading(true);
        setError(null);
        try {
            await rejectAiBatch(data.batch.id);
            setSuccessMsg(`Batch #${data.batch.id} rejected.`);
            fetchData();
        } catch (err: any) {
            setError(err.message || "Failed to reject AI modifications");
        } finally {
            setActionLoading(false);
        }
    };

    // Auto-clear success message
    useEffect(() => {
        if (successMsg) {
            const t = setTimeout(() => setSuccessMsg(null), 5000);
            return () => clearTimeout(t);
        }
    }, [successMsg]);

    return (
        <div className="max-w-5xl mx-auto p-6">

            <div className="flex justify-between items-center mb-6">
                <div>
                    <h1 className="text-3xl font-bold bg-gradient-to-r from-purple-400 to-blue-400 bg-clip-text text-transparent flex items-center">
                        <Sparkles size={28} className="mr-3 text-purple-400" />
                        Strategy Improvements
                    </h1>
                    <p className="text-gray-400 mt-2">AI-proposed tuning suggestions based on your continuous Trade Journal feedback.</p>
                </div>
                <button
                    onClick={fetchData}
                    disabled={loading || actionLoading}
                    className="p-3 bg-gray-800 hover:bg-gray-700 text-gray-300 rounded-lg border border-gray-700 transition-colors disabled:opacity-50"
                >
                    <RefreshCcw size={20} className={loading ? "animate-spin" : ""} />
                </button>
            </div>

            {/* Persistent UI Rules Banner */}
            <div className="bg-blue-900/20 border border-blue-800/50 p-4 rounded-lg flex items-start mb-6">
                <Info className="text-blue-400 mr-3 shrink-0 mt-0.5" size={20} />
                <div className="text-blue-200 text-sm">
                    <strong>AI proposes changes; you decide.</strong> No auto-changes will ever be applied natively. AI reviews aggregate feedback data every 10 trades and highlights parameters that could safely improve outcome probability.
                </div>
            </div>

            {error && (
                <div className="mb-6"><Banner message={error} onRetry={fetchData} /></div>
            )}

            {successMsg && (
                <div className="bg-green-900/40 border border-green-500 text-green-300 p-4 rounded mb-6 font-bold shadow-lg">
                    {successMsg}
                </div>
            )}

            {loading && !data ? (
                <div className="py-12 text-center text-gray-500 animate-pulse">Loading strategy intelligence...</div>
            ) : data ? (
                <>
                    <AiStatusCard activeVersion={data.currentActiveConfigVersion} />

                    {data.batch && data.batch.status === 'PROPOSED' ? (
                        <AiBatchCard
                            batch={data.batch}
                            onAccept={handleAcceptClick}
                            onReject={handleReject}
                            actionLoading={actionLoading}
                            actionsDisabled={!canAcceptReject}
                            actionsDisabledReason={aiActionDisabledTooltip}
                        />
                    ) : (
                        <div className="bg-gray-800 rounded-lg p-12 text-center border border-gray-700 mt-6">
                            <Sparkles className="mx-auto text-gray-600 mb-4" size={48} />
                            <h3 className="text-xl font-medium text-gray-300">No suggestions pending</h3>
                            <p className="text-gray-500 mt-2 max-w-lg mx-auto leading-relaxed">
                                The system is accumulating statistical significance. Complete 10 manually tracked trades using the Journal to generate the next iteration of intelligent strategy parameter proposals.
                            </p>
                        </div>
                    )}
                </>
            ) : null}

            <ConfirmModal
                isOpen={showConfirmModal}
                onCancel={() => setShowConfirmModal(false)}
                onConfirm={handleConfirmAccept}
                loading={actionLoading}
            />

        </div>
    );
};
