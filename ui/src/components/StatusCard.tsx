import React from 'react';
import { Activity, Clock } from 'lucide-react';
import type { StatusResponse } from '../api/client';

interface StatusCardProps {
    status: StatusResponse | null;
    loading: boolean;
}

export const StatusCard: React.FC<StatusCardProps> = ({ status, loading }) => {
    if (loading && !status) {
        return (
            <div className="bg-gray-800 rounded-lg p-6 animate-pulse">
                <div className="h-4 bg-gray-700 rounded w-1/4 mb-4"></div>
                <div className="h-10 bg-gray-700 rounded mb-2"></div>
            </div>
        );
    }

    return (
        <div className="bg-gray-800 rounded-lg p-6 shadow-lg border border-gray-700">
            <div className="flex items-center mb-4">
                <Activity className="text-blue-400 mr-2" />
                <h2 className="text-xl font-bold text-gray-100">Backend Status</h2>
            </div>

            <div className="grid grid-cols-2 gap-4">
                <div>
                    <p className="text-sm text-gray-400">Uptime</p>
                    <p className="text-lg font-mono text-gray-200">
                        {status ? `${Math.floor(status.uptimeSeconds / 60)}m ${status.uptimeSeconds % 60}s` : 'Unknown'}
                    </p>
                </div>
                <div>
                    <p className="text-sm text-gray-400">Last Scan</p>
                    <p className="text-lg font-mono text-gray-200">
                        {status?.lastScanTime ? new Date(status.lastScanTime).toLocaleTimeString() : 'Never'}
                    </p>
                </div>
                <div className="col-span-2">
                    <p className="text-sm text-gray-400 flex items-center">
                        <Clock size={14} className="mr-1" /> Next Scheduled Scan
                    </p>
                    <p className="text-lg font-mono text-gray-200">
                        {status?.nextScanTime ? new Date(status.nextScanTime).toLocaleTimeString() : 'Unknown'}
                    </p>
                </div>
            </div>
        </div>
    );
};
