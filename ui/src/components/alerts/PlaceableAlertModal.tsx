import React, { useEffect, useMemo, useState } from 'react';
import { BellRing, Clock3, Square, X } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { stopAlertSound } from '../../alerts/alertAudio';
import { useAlertsStore } from '../../store/alertsStore';

export const PlaceableAlertModal: React.FC = () => {
    const navigate = useNavigate();
    const modalAlert = useAlertsStore((state) => state.modalAlert);
    const modalLockUntilEpochMs = useAlertsStore((state) => state.modalLockUntilEpochMs);
    const dismissModal = useAlertsStore((state) => state.dismissModal);
    const snoozeForMinutes = useAlertsStore((state) => state.snoozeForMinutes);

    const [nowEpochMs, setNowEpochMs] = useState(Date.now());

    useEffect(() => {
        if (!modalAlert) {
            return;
        }

        setNowEpochMs(Date.now());
        const timer = window.setInterval(() => {
            setNowEpochMs(Date.now());
        }, 250);

        return () => {
            window.clearInterval(timer);
        };
    }, [modalAlert]);

    const lockRemainingSec = useMemo(() => {
        if (!modalLockUntilEpochMs) {
            return 0;
        }
        return Math.max(0, Math.ceil((modalLockUntilEpochMs - nowEpochMs) / 1000));
    }, [modalLockUntilEpochMs, nowEpochMs]);

    const locked = lockRemainingSec > 0;

    if (!modalAlert) {
        return null;
    }

    const handleOpenRecommendation = () => {
        if (locked) {
            return;
        }
        dismissModal();
        navigate(`/recommendation/${modalAlert.recommendationId}`);
    };

    const handleSnooze = () => {
        if (locked) {
            return;
        }
        snoozeForMinutes(30);
        stopAlertSound();
    };

    const handleDismiss = () => {
        if (locked) {
            return;
        }
        dismissModal();
    };

    const handleStopSound = () => {
        stopAlertSound();
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
            <div className="w-full max-w-2xl overflow-hidden rounded-xl border border-amber-500/70 bg-gray-900 shadow-2xl">
                <div className="border-b border-amber-600/70 bg-amber-700/25 p-6">
                    <div className="flex items-center gap-3">
                        <BellRing className="text-amber-300" size={28} />
                        <div>
                            <h2 className="text-2xl font-black text-amber-200 tracking-wide">PLACEABLE TRADE FOUND</h2>
                            <p className="mt-1 text-sm text-amber-100/90">
                                {modalAlert.symbol} — {modalAlert.side} — Placeable now (Mark ok).
                            </p>
                        </div>
                    </div>
                </div>

                <div className="p-6">
                    <div className="mb-4 rounded-lg border border-amber-700/40 bg-gray-800/70 p-3 text-sm text-gray-200">
                        Keep this alert visible for at least 10 seconds before action.
                    </div>

                    {locked && (
                        <div className="mb-4 flex items-center gap-2 rounded-lg border border-indigo-600/50 bg-indigo-900/20 p-3 text-sm text-indigo-200">
                            <Clock3 size={16} className="shrink-0" />
                            Actions unlock in {lockRemainingSec}s
                        </div>
                    )}

                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                        <button
                            onClick={handleOpenRecommendation}
                            disabled={locked}
                            className="rounded-md border border-emerald-500/60 bg-emerald-800/25 px-4 py-3 text-sm font-bold text-emerald-200 hover:bg-emerald-700/35 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            Open Recommendation
                        </button>

                        <button
                            onClick={handleSnooze}
                            disabled={locked}
                            className="rounded-md border border-blue-500/60 bg-blue-800/20 px-4 py-3 text-sm font-bold text-blue-200 hover:bg-blue-700/35 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            Snooze 30 min
                        </button>

                        <button
                            onClick={handleDismiss}
                            disabled={locked}
                            className="rounded-md border border-gray-500/50 bg-gray-700/30 px-4 py-3 text-sm font-bold text-gray-200 hover:bg-gray-600/40 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            <span className="inline-flex items-center gap-2">
                                <X size={14} /> Dismiss
                            </span>
                        </button>

                        <button
                            onClick={handleStopSound}
                            className="rounded-md border border-rose-500/70 bg-rose-700/20 px-4 py-3 text-sm font-bold text-rose-200 hover:bg-rose-600/35"
                        >
                            <span className="inline-flex items-center gap-2">
                                <Square size={14} /> Stop sound
                            </span>
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
};
