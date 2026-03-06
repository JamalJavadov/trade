import React from 'react';
import { AlertCircle, RefreshCw } from 'lucide-react';

interface BannerProps {
    message: string;
    onRetry?: () => void;
}

export const Banner: React.FC<BannerProps> = ({ message, onRetry }) => {
    return (
        <div className="bg-red-900 border-l-4 border-red-500 p-4 mb-6 rounded shadow-lg flex items-center justify-between">
            <div className="flex items-center">
                <AlertCircle className="text-red-400 mr-3" size={24} />
                <p className="text-red-100 font-medium">{message}</p>
            </div>
            {onRetry && (
                <button
                    onClick={onRetry}
                    className="flex items-center text-sm bg-red-800 hover:bg-red-700 text-red-100 py-1 px-3 rounded transition-colors"
                >
                    <RefreshCw size={14} className="mr-2" /> Retry
                </button>
            )}
        </div>
    );
};
