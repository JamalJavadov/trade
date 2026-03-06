import React, { useState } from 'react';
import { useErrorStore } from '../../store/errorStore';
import { AlertCircle, X, ChevronRight } from 'lucide-react';
import { getErrorExplanation } from '../../utils/errorMap';
import { ErrorModal } from './ErrorModal';

export const ErrorBanner: React.FC = () => {
    const { errors, resolveError } = useErrorStore();
    const [isModalOpen, setIsModalOpen] = useState(false);

    // Get the latest unresolved error
    const activeError = errors.find(e => !e.resolved);

    if (!activeError) return null;

    const explainer = getErrorExplanation(activeError.errorCode);

    const handleDismiss = (e: React.MouseEvent) => {
        e.stopPropagation();
        resolveError(activeError.id);
    };

    return (
        <>
            <div
                onClick={() => setIsModalOpen(true)}
                className="bg-rose-500/10 border-b border-rose-500/20 px-4 py-3 cursor-pointer hover:bg-rose-500/15 transition-colors relative z-40"
            >
                <div className="max-w-7xl mx-auto flex items-start sm:items-center justify-between gap-4">
                    <div className="flex flex-col sm:flex-row sm:items-center gap-2 sm:gap-4 flex-1">
                        <div className="flex items-center gap-2 text-rose-400 font-bold shrink-0">
                            <AlertCircle size={18} />
                            <span>{activeError.errorCode}</span>
                        </div>
                        <div className="text-sm text-rose-200/80 truncate">
                            {explainer.why} <span className="opacity-60 font-mono ml-2">Click for details & fix steps</span>
                        </div>
                    </div>

                    <div className="flex items-center gap-3 shrink-0">
                        <ChevronRight size={18} className="text-rose-400/50 hidden sm:block" />
                        <button
                            onClick={handleDismiss}
                            className="p-1 hover:bg-rose-500/20 text-rose-400/70 hover:text-rose-400 rounded transition-colors"
                            title="Dismiss"
                        >
                            <X size={18} />
                        </button>
                    </div>
                </div>
            </div>

            <ErrorModal
                error={activeError}
                isOpen={isModalOpen}
                onClose={() => setIsModalOpen(false)}
            />
        </>
    );
};
