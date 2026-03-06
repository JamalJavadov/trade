import React from 'react';
import type { RecommendationDTO } from '../api/client';
import { ArrowUpRight, ArrowDownRight, Target, Shield, Coins } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

interface RecommendationCardProps {
    recommendation: RecommendationDTO | null;
    loading: boolean;
}

export const RecommendationCard: React.FC<RecommendationCardProps> = ({ recommendation, loading }) => {
    const navigate = useNavigate();

    if (loading && !recommendation) {
        return (
            <div className="bg-gray-800 rounded-lg p-6 animate-pulse mt-6 h-48"></div>
        );
    }

    if (!recommendation) {
        return (
            <div className="bg-gray-800 rounded-lg p-6 mt-6 border border-gray-700 flex flex-col items-center justify-center h-48">
                <Target className="text-gray-500 mb-2" size={32} />
                <h3 className="text-xl text-gray-400 font-medium">No active trade recommendations</h3>
                <p className="text-sm text-gray-500 mt-1">Run a scan to find setups</p>
            </div>
        );
    }

    const isLong = recommendation.side === "BUY";

    return (
        <div className="bg-gray-800 rounded-lg p-6 mt-6 border border-gray-700 shadow-xl relative overflow-hidden">
            <div className={`absolute top-0 left-0 w-2 h-full ${isLong ? 'bg-green-500' : 'bg-red-500'}`}></div>

            <div className="flex justify-between items-start mb-6 pl-4">
                <div>
                    <div className="flex items-center space-x-3 mb-1">
                        <h2 className="text-3xl font-black text-white tracking-widest">{recommendation.symbol}</h2>
                        <span className={`px-3 py-1 text-xs font-bold rounded-full flex items-center ${isLong ? 'bg-green-500/20 text-green-400 border border-green-500/30'
                            : 'bg-red-500/20 text-red-400 border border-red-500/30'
                            }`}>
                            {isLong ? <ArrowUpRight size={14} className="mr-1" /> : <ArrowDownRight size={14} className="mr-1" />}
                            {recommendation.side}
                        </span>
                    </div>
                    <p className="text-gray-400 text-sm">{recommendation.rationaleText}</p>
                </div>

                <div className="bg-gray-900 px-4 py-2 rounded border border-gray-700 text-center">
                    <p className="text-xs text-gray-500 uppercase tracking-wider mb-1">Risk/Reward</p>
                    <p className="text-xl font-mono font-bold text-yellow-400">
                        {recommendation.confidenceScore.toFixed(2)}R
                    </p>
                </div>
            </div>

            <div className="grid grid-cols-3 gap-4 pl-4 mb-6">
                <div className="bg-gray-750 p-3 rounded bg-gray-900/50">
                    <p className="text-xs text-gray-500 flex items-center mb-1"><Target size={12} className="mr-1" /> Entry</p>
                    <p className="font-mono text-white text-lg">{recommendation.entryOrder?.stopPrice || "MARKET"}</p>
                </div>
                <div className="bg-gray-750 p-3 rounded bg-gray-900/50">
                    <p className="text-xs text-red-400 flex items-center mb-1"><Shield size={12} className="mr-1" /> Stop Loss</p>
                    <p className="font-mono text-white text-lg">{recommendation.slOrder?.stopPrice}</p>
                </div>
                <div className="bg-gray-750 p-3 rounded bg-gray-900/50">
                    <p className="text-xs text-green-400 flex items-center mb-1"><Coins size={12} className="mr-1" /> Take Profit 1</p>
                    <p className="font-mono text-white text-lg">{recommendation.tpOrder?.stopPrice}</p>
                </div>
            </div>

            <div className="pl-4">
                <button
                    onClick={() => navigate(`/recommendation/${recommendation.id}`)}
                    className="w-full bg-gray-700 hover:bg-gray-600 text-white font-medium py-3 rounded transition-colors"
                >
                    View Manual Order Details
                </button>
            </div>

        </div>
    );
};
