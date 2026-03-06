import React from 'react';
import { BellRing, X } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useAlertsStore } from '../../store/alertsStore';

export const PlaceableAlertBanner: React.FC = () => {
    const navigate = useNavigate();
    const bannerAlert = useAlertsStore((state) => state.bannerAlert);
    const dismissBanner = useAlertsStore((state) => state.dismissBanner);

    if (!bannerAlert) {
        return null;
    }

    const handleOpen = () => {
        navigate(`/recommendation/${bannerAlert.recommendationId}`);
    };

    return (
        <div className="mb-6 rounded-lg border-l-4 border-amber-400 bg-amber-900/50 p-4 shadow-lg">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div className="flex items-start gap-3">
                    <BellRing className="mt-0.5 text-amber-300" size={22} />
                    <div>
                        <p className="text-sm font-black tracking-wide text-amber-100">PLACEABLE TRADE FOUND</p>
                        <p className="text-sm text-amber-100/90">
                            {bannerAlert.symbol} — {bannerAlert.side} — Placeable now (Mark ok).
                        </p>
                    </div>
                </div>

                <div className="flex gap-2">
                    <button
                        onClick={handleOpen}
                        className="rounded border border-emerald-500/60 bg-emerald-700/20 px-3 py-1.5 text-xs font-bold text-emerald-200 hover:bg-emerald-700/35"
                    >
                        Open Recommendation
                    </button>
                    <button
                        onClick={dismissBanner}
                        className="rounded border border-gray-500/60 bg-gray-700/20 px-3 py-1.5 text-xs font-bold text-gray-200 hover:bg-gray-600/35"
                    >
                        <span className="inline-flex items-center gap-1">
                            <X size={12} /> Dismiss
                        </span>
                    </button>
                </div>
            </div>
        </div>
    );
};
