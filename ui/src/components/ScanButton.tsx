import React, { useState } from 'react';
import { Zap } from 'lucide-react';
import { runScanOnce } from '../api/client';
import { usePermissions } from '../hooks/usePermissions';
import { disabledByPermissionTooltip } from '../utils/permissionUi';

interface ScanButtonProps {
    onScanStart: () => void;
    onScanComplete: () => void;
}

export const ScanButton: React.FC<ScanButtonProps> = ({ onScanStart, onScanComplete }) => {
    const { can } = usePermissions();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const canRunScanNow = can('scan.run_once');
    const runScanTooltip = disabledByPermissionTooltip('scan.run_once', canRunScanNow);

    const handleScan = async () => {
        if (!canRunScanNow) {
            return;
        }

        setLoading(true);
        setError(null);
        onScanStart();
        try {
            await runScanOnce();
            // Assume scan takes around 15-20s. We just fire and wait for auto-poll to pick it up, 
            // but let's artificially wait before re-enabling poll.
            setTimeout(() => {
                setLoading(false);
                onScanComplete();
            }, 5000);
        } catch (err: any) {
            setError(err.message);
            setLoading(false);
            onScanComplete();
        }
    };

    return (
        <div className="flex flex-col">
                <button
                    onClick={handleScan}
                    disabled={loading || !canRunScanNow}
                    title={runScanTooltip}
                    className={`flex items-center justify-center py-3 px-6 rounded-lg font-bold text-lg shadow-lg transition-all ${loading
                        ? 'bg-blue-600/50 text-gray-300 cursor-not-allowed'
                        : 'bg-blue-600 hover:bg-blue-500 text-white hover:scale-105 active:scale-95'
                    }`}
            >
                <Zap className={`mr-2 ${loading ? 'animate-pulse text-yellow-400' : ''}`} />
                {loading ? 'Scanning Market...' : 'Run Scan Now'}
            </button>
            {error && <p className="text-red-400 text-sm mt-2">{error}</p>}
        </div>
    );
};
