import React, { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useScanReplay } from '../hooks/useScanReplay';
import { Play, Pause, SkipBack, ArrowLeft, Activity } from 'lucide-react';
import { EvaluationsTable } from '../components/scan/EvaluationsTable';
import { CoinDetailDrawer } from '../components/scan/CoinDetailDrawer';

export const ScanReplayPage: React.FC = () => {
    const { scanRunId } = useParams<{ scanRunId: string }>();
    const navigate = useNavigate();

    const { data, loading, error, playback, derived } = useScanReplay(scanRunId);

    // State for the drawer
    const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null);

    if (loading) return <div className="p-8 text-center text-slate-400">Loading replay data...</div>;
    if (error || !data) return <div className="p-8 text-center text-rose-400">Error: {error || "Not found"}</div>;

    const { summary, evaluations } = data;

    return (
        <div className="max-w-6xl mx-auto space-y-6 pb-10">
            {/* Header */}
            <div className="flex justify-between items-center bg-slate-800 p-6 rounded-xl border border-slate-700 shadow-lg top-0 relative">
                <div className="flex items-center gap-4">
                    <button
                        onClick={() => navigate('/scan/history')}
                        className="p-2 bg-slate-700 hover:bg-slate-600 text-slate-300 rounded-lg transition-colors border border-slate-600"
                    >
                        <ArrowLeft size={18} />
                    </button>
                    <div>
                        <h1 className="text-2xl font-bold tracking-tight text-white mb-1 flex items-center gap-3">
                            Scan Replay
                            <span className="flex items-center gap-1.5 px-2.5 py-1 rounded bg-blue-500/10 text-blue-400 border border-blue-500/20 text-xs tracking-wider uppercase">
                                <Activity size={12} />
                                Offline Replay
                            </span>
                        </h1>
                        <p className="text-slate-400 text-sm">Replaying scan {scanRunId?.split('-')[0]}</p>
                    </div>
                </div>
                <div className="flex items-center gap-3">
                    <div className="bg-slate-900 border border-slate-700 rounded-lg px-4 py-2 flex flex-col items-center">
                        <span className="text-[10px] uppercase text-slate-500 font-bold tracking-wider mb-0.5">Phase</span>
                        <span className="text-sm font-mono text-indigo-400">{derived.currentPhase}</span>
                    </div>
                    <div className="bg-slate-900 border border-slate-700 rounded-lg px-4 py-2 flex flex-col items-center">
                        <span className="text-[10px] uppercase text-slate-500 font-bold tracking-wider mb-0.5">Total Evals</span>
                        <span className="text-sm font-mono text-emerald-400">{summary.evaluatedCount}</span>
                    </div>
                </div>
            </div>

            {/* Player Controls */}
            <div className="bg-slate-800 border border-slate-700 rounded-xl shadow-lg p-6">
                <div className="flex flex-col gap-4">
                    <div className="flex justify-between items-center">
                        <div className="flex items-center gap-4">
                            <button
                                className="w-12 h-12 flex items-center justify-center bg-indigo-600 hover:bg-indigo-500 text-white rounded-full shadow-lg transition-colors"
                                onClick={playback.togglePlay}
                            >
                                {playback.isPlaying ? <Pause className="fill-white" size={20} /> : <Play className="fill-white ml-1" size={20} />}
                            </button>
                            <button
                                className="p-2 bg-slate-700 hover:bg-slate-600 text-slate-300 rounded-lg transition-colors border border-slate-600"
                                onClick={playback.reset}
                                title="Reset to beginning"
                            >
                                <SkipBack size={18} />
                            </button>
                            <div className="font-mono text-sm text-slate-300 w-24">
                                {(playback.progressMs / 1000).toFixed(1)}s / {(playback.totalDurationMs / 1000).toFixed(1)}s
                            </div>
                        </div>
                        <div className="flex items-center gap-2">
                            <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider mr-2">Playback Speed:</span>
                            {[1, 5, 10, 25].map(s => (
                                <button
                                    key={s}
                                    className={`px-3 py-1 rounded text-xs font-bold transition-colors ${playback.speed === s
                                            ? 'bg-indigo-500 text-white shadow-md'
                                            : 'bg-slate-700 text-slate-400 hover:bg-slate-600'
                                        }`}
                                    onClick={() => playback.setSpeed(s)}
                                >
                                    {s}x
                                </button>
                            ))}
                        </div>
                    </div>

                    <div className="relative pt-4 pb-2 w-full">
                        <input
                            type="range"
                            min="0"
                            max="100"
                            value={playback.progressPct * 100}
                            onChange={(e) => playback.seek(parseFloat(e.target.value) / 100)}
                            className="w-full h-2 bg-slate-700 rounded-lg appearance-none cursor-pointer accent-indigo-500"
                        />
                    </div>
                </div>
            </div>

            {/* Current Best Emergence Visualization */}
            <div className="bg-slate-800 border-l-4 border-l-emerald-500 rounded-r-xl shadow-lg p-5">
                <div className="flex justify-between items-center">
                    <div>
                        <h4 className="text-[10px] font-bold uppercase tracking-wider text-slate-500 mb-1">Current Best Emergence</h4>
                        {derived.currentBestCandidate ? (
                            <div className="flex items-center gap-3">
                                <span className="text-2xl font-bold text-emerald-400">{derived.currentBestCandidate.symbol}</span>
                                <span className={`px-2 py-0.5 rounded text-[10px] font-bold tracking-wider ${derived.currentBestCandidate.side === 'LONG'
                                        ? 'bg-emerald-500/10 text-emerald-500 border border-emerald-500/20'
                                        : 'bg-rose-500/10 text-rose-500 border border-rose-500/20'
                                    }`}>
                                    {derived.currentBestCandidate.side}
                                </span>
                            </div>
                        ) : (
                            <div className="text-slate-400 italic text-sm mt-1">Evaluating universe structure...</div>
                        )}
                    </div>
                    {derived.currentBestCandidate && (
                        <div className="text-right">
                            <div className="text-[10px] font-bold uppercase tracking-wider text-slate-500 mb-1">Final Score</div>
                            <div className="text-xl font-mono text-white">{derived.currentBestCandidate.finalScore.toFixed(3)}</div>
                        </div>
                    )}
                </div>
            </div>

            <div className="h-[500px] w-full mt-6">
                <EvaluationsTable
                    rows={evaluations}
                    onRowClick={setSelectedSymbol}
                />
            </div>

            <CoinDetailDrawer
                scanRunId={scanRunId || null}
                symbol={selectedSymbol}
                onClose={() => setSelectedSymbol(null)}
            />
        </div>
    );
};
