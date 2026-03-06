import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getHistoricalScans } from '../api/scan';
import type { Page, ScanSummaryDTO } from '../types/scan';
import { Play } from 'lucide-react';

export const ScanHistoryPage: React.FC = () => {
    const navigate = useNavigate();
    const [scansPage, setScansPage] = useState<Page<ScanSummaryDTO> | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const fetchScans = async () => {
            setLoading(true);
            try {
                const data = await getHistoricalScans(20, 0);
                setScansPage(data);
            } catch (err) {
                console.error("Failed to load historical scans", err);
            } finally {
                setLoading(false);
            }
        };
        fetchScans();
    }, []);

    const formatDate = (dateString: string) => {
        return new Date(dateString).toLocaleString(undefined, {
            month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit'
        });
    };

    const getStatusColor = (status: string) => {
        switch (status) {
            case 'COMPLETED': return 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20';
            case 'FAILED': return 'bg-rose-500/10 text-rose-400 border-rose-500/20';
            case 'IN_PROGRESS': return 'bg-blue-500/10 text-blue-400 border-blue-500/20';
            default: return 'bg-slate-700 text-slate-400 border-slate-600';
        }
    };

    return (
        <div className="max-w-6xl mx-auto space-y-6">
            <div className="flex justify-between items-center bg-slate-800 p-6 rounded-xl border border-slate-700 shadow-lg top-0 relative">
                <div>
                    <h1 className="text-2xl font-bold tracking-tight text-white mb-1">Scan History</h1>
                    <p className="text-slate-400 text-sm">Review past market scans and replay their emergence.</p>
                </div>
                <button
                    onClick={() => navigate('/scan')}
                    className="px-4 py-2 bg-slate-700 hover:bg-slate-600 text-white rounded-lg font-medium transition-colors text-sm border border-slate-600"
                >
                    Current Scan
                </button>
            </div>

            <div className="bg-slate-800 border border-slate-700 rounded-xl shadow-lg overflow-hidden flex flex-col min-h-[500px]">
                <div className="grid grid-cols-6 gap-2 px-4 py-3 bg-slate-900/50 border-b border-slate-700 text-xs font-semibold text-slate-400 uppercase tracking-wider sticky top-0 z-10">
                    <div className="col-span-1">Started At</div>
                    <div className="col-span-1">Status</div>
                    <div className="col-span-1 text-center">Evaluated</div>
                    <div className="col-span-1 text-center">Valid Trades</div>
                    <div className="col-span-1 text-center">Best Mkt</div>
                    <div className="col-span-1 text-right">Actions</div>
                </div>

                <div className="flex-1 overflow-auto bg-slate-800">
                    {loading ? (
                        <div className="flex items-center justify-center h-48 text-slate-500 text-sm">Loading historical scans...</div>
                    ) : scansPage?.content.length === 0 ? (
                        <div className="flex items-center justify-center h-48 text-slate-500 text-sm">No scans found.</div>
                    ) : (
                        <div className="divide-y divide-slate-700/50">
                            {scansPage?.content.map((scan) => (
                                <div key={scan.id} className="grid grid-cols-6 gap-2 px-4 py-4 items-center hover:bg-slate-700/30 transition-colors">
                                    <div className="col-span-1 text-sm font-medium text-slate-300">
                                        {formatDate(scan.startedAt)}
                                    </div>
                                    <div className="col-span-1">
                                        <span className={`px-2 py-1 rounded text-xs font-bold tracking-wider border ${getStatusColor(scan.status)}`}>
                                            {scan.status}
                                        </span>
                                    </div>
                                    <div className="col-span-1 text-center text-sm text-slate-300">
                                        {scan.evaluatedCount} / {scan.topN}
                                    </div>
                                    <div className="col-span-1 text-center text-sm font-medium">
                                        {scan.validCount > 0
                                            ? <span className="text-emerald-400">{scan.validCount}</span>
                                            : <span className="text-slate-500">0</span>}
                                    </div>
                                    <div className="col-span-1 text-center text-sm">
                                        {scan.bestRecommendationId
                                            ? <span className="text-emerald-400 font-medium">Yes</span>
                                            : <span className="text-slate-500">No</span>}
                                    </div>
                                    <div className="col-span-1 flex justify-end">
                                        <button
                                            className="px-3 py-1.5 flex items-center gap-2 bg-indigo-500/20 text-indigo-300 hover:bg-indigo-500/30 border border-indigo-500/30 rounded-lg text-xs font-semibold transition-colors"
                                            onClick={() => navigate(`/replay/${scan.id}`)}
                                        >
                                            <Play size={14} className="fill-indigo-300" />
                                            REPLAY
                                        </button>
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};
