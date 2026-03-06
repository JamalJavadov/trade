import React from 'react';
import type { AiSuggestionBatchDTO } from '../api/client';
import { SuggestionItemsTable } from './SuggestionItemsTable';
import { Clock, Activity } from 'lucide-react';

interface AiBatchCardProps {
    batch: AiSuggestionBatchDTO;
    onAccept: () => void;
    onReject: () => void;
    actionLoading: boolean;
    actionsDisabled?: boolean;
    actionsDisabledReason?: string;
}

export const AiBatchCard: React.FC<AiBatchCardProps> = ({
    batch,
    onAccept,
    onReject,
    actionLoading,
    actionsDisabled = false,
    actionsDisabledReason,
}) => {
    return (
        <div className="bg-gray-800 rounded-lg p-6 border border-gray-700 shadow-xl mt-6">

            <div className="flex flex-col md:flex-row justify-between items-start md:items-center mb-6">
                <div>
                    <h3 className="text-2xl font-black text-white tracking-widest flex items-center">
                        PROPOSED BATCH #{batch.id}
                        <span className="ml-3 px-2 py-1 bg-yellow-900/40 text-yellow-400 text-xs rounded border border-yellow-700/50 uppercase">
                            Pending Your Review
                        </span>
                    </h3>
                    <div className="flex space-x-4 mt-2 text-sm text-gray-400">
                        <span className="flex items-center"><Clock size={14} className="mr-1" /> {new Date(batch.createdAt).toLocaleString()}</span>
                        <span className="flex items-center"><Activity size={14} className="mr-1" /> Based on last {batch.basedOnLastNTrades} trades</span>
                    </div>
                </div>
            </div>

            <div className="bg-gray-900/50 p-4 rounded text-gray-300 italic mb-6 border-l-4 border-blue-500">
                "{batch.summary}"
            </div>

            <SuggestionItemsTable items={batch.items} />

            <div className="mt-8 flex flex-col sm:flex-row space-y-3 sm:space-y-0 sm:space-x-4">
                <button
                    onClick={onReject}
                    disabled={actionLoading || actionsDisabled}
                    title={actionsDisabledReason}
                    className="flex-1 py-3 bg-gray-700 hover:bg-gray-600 border border-gray-600 rounded-lg font-bold text-white transition-colors disabled:opacity-50"
                >
                    Reject All Suggestions
                </button>
                <button
                    onClick={onAccept}
                    disabled={actionLoading || actionsDisabled}
                    title={actionsDisabledReason}
                    className="flex-1 py-3 bg-blue-600 hover:bg-blue-500 border border-blue-500 rounded-lg font-bold text-white shadow-lg transition-colors disabled:opacity-50"
                >
                    Accept & Activate New Config
                </button>
            </div>

        </div>
    );
};
