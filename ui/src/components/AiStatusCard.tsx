import React from 'react';
import { Server } from 'lucide-react';

interface AiStatusCardProps {
    activeVersion: number;
}

export const AiStatusCard: React.FC<AiStatusCardProps> = ({ activeVersion }) => {
    return (
        <div className="bg-gray-800 rounded-lg p-6 border border-gray-700 shadow-xl relative overflow-hidden flex items-center justify-between">
            <div>
                <h3 className="text-xl font-bold text-gray-200">AI Strategy Tuner</h3>
                <p className="text-gray-400 text-sm mt-1">OpenRouter Feedback Loop</p>
            </div>
            <div className="bg-gray-900 border border-gray-700 px-4 py-2 rounded-lg flex items-center space-x-3">
                <Server className="text-blue-400" size={20} />
                <div>
                    <p className="text-xs text-gray-500 uppercase font-bold tracking-widest">Active Config</p>
                    <p className="text-lg font-mono text-white text-right">v{activeVersion}</p>
                </div>
            </div>
        </div>
    );
};
