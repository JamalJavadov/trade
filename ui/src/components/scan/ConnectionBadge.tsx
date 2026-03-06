import { WifiOff, Loader2 } from 'lucide-react';

export function ConnectionBadge({
    connected,
    reconnecting,
    polling
}: {
    connected: boolean;
    reconnecting: boolean;
    polling: boolean;
}) {
    if (reconnecting) {
        return (
            <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium bg-amber-500/10 text-amber-400 border border-amber-500/20">
                <Loader2 className="w-3 h-3 animate-spin" />
                Reconnecting
            </span>
        );
    }

    if (polling) {
        return (
            <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium bg-sky-500/10 text-sky-400 border border-sky-500/20">
                <Loader2 className="w-3 h-3 animate-spin" />
                Polling
            </span>
        );
    }

    if (connected) {
        return (
            <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                <span className="relative flex h-2 w-2">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                    <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500"></span>
                </span>
                Live
            </span>
        );
    }

    return (
        <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium bg-slate-500/10 text-slate-400 border border-slate-500/20">
            <WifiOff className="w-3 h-3" />
            Offline
        </span>
    );
}
