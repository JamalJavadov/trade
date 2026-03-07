import React from 'react';
import { motion } from 'framer-motion';
import type { ScanCandidateEventDTO, ScanPhaseDTO } from '../../types/scan';
import { CheckCircle2, Circle, Loader2, XCircle } from 'lucide-react';

const EXPECTED_PHASES = [
    "EXCHANGE_INFO",
    "TICKER_24H",
    "UNIVERSE_TOP300",
    "STRATEGY_EVAL",
    "RANKING",
    "PERSIST",
    "DONE"
];

function formatPhaseName(phase: string) {
    return phase.replace(/_/g, ' ').toLowerCase()
        .replace(/\b\w/g, c => c.toUpperCase());
}

export function PhaseTimeline({
    phases,
    scanStatus,
    latestCandidateEvent
}: {
    phases: ScanPhaseDTO[],
    scanStatus: string,
    latestCandidateEvent?: ScanCandidateEventDTO | null
}) {

    const getPhaseState = (expectedPhase: string) => {
        const found = phases.find(p => p.phase === expectedPhase);
        if (found) {
            if (found.status === 'FINISHED') return 'completed';
            if (found.status === 'FAILED') return 'failed';
            return 'running';
        }

        // If scan is FAILED and this phase wasn't reached, it's skipped
        if (scanStatus === 'FAILED') return 'skipped';

        // If it's the next phase to run, mark it waiting
        const lastFinishedIdx = [...phases].reverse().findIndex(p => p.status === 'FINISHED');
        const lastFinishedPhase = lastFinishedIdx >= 0 ? phases[phases.length - 1 - lastFinishedIdx].phase : null;

        const expectedIdx = EXPECTED_PHASES.indexOf(expectedPhase);
        const lastIdx = lastFinishedPhase ? EXPECTED_PHASES.indexOf(lastFinishedPhase) : -1;

        if (scanStatus === 'STARTED' && expectedIdx === lastIdx + 1) return 'next';
        return 'waiting';
    };

    return (
        <div className="bg-slate-800 border border-slate-700/50 p-4 rounded-xl shadow-lg overflow-x-auto">
            <h3 className="text-sm font-medium text-slate-400 mb-4 px-2">Pipeline Execution</h3>
            <div className="flex items-center min-w-[600px] px-2 pb-2">
                {EXPECTED_PHASES.map((phase, idx) => {
                    const state = getPhaseState(phase);
                    const isLast = idx === EXPECTED_PHASES.length - 1;

                    return (
                        <React.Fragment key={phase}>
                            <div className="flex flex-col items-center relative gap-2 w-24">
                                <motion.div
                                    initial={{ scale: 0.8, opacity: 0 }}
                                    animate={{ scale: 1, opacity: 1 }}
                                    className={`relative z-10 flex items-center justify-center bg-slate-800 rounded-full
                                        ${state === 'completed' ? 'text-emerald-500' :
                                            state === 'running' || state === 'next' ? 'text-blue-400' :
                                                state === 'failed' ? 'text-rose-500' :
                                                    'text-slate-600'}`
                                    }
                                >
                                    {state === 'completed' ? <CheckCircle2 className="w-6 h-6" /> :
                                        state === 'failed' ? <XCircle className="w-6 h-6" /> :
                                            state === 'running' || state === 'next' ? <Loader2 className="w-6 h-6 animate-spin" /> :
                                                <Circle className="w-6 h-6" />}

                                </motion.div>
                                <span className={`text-[10px] font-medium text-center leading-tight
                                    ${state === 'completed' ? 'text-slate-300' :
                                        state === 'failed' ? 'text-rose-400' :
                                            state === 'running' || state === 'next' ? 'text-blue-300' :
                                                'text-slate-500'}`
                                }>
                                    {formatPhaseName(phase)}
                                </span>
                            </div>

                            {!isLast && (
                                <div className="flex-1 h-[2px] -mt-5 bg-slate-700 relative overflow-hidden">
                                    {state === 'completed' && (
                                        <motion.div
                                            className="absolute inset-0 bg-emerald-500/50"
                                            initial={{ x: '-100%' }}
                                            animate={{ x: 0 }}
                                            transition={{ duration: 0.4 }}
                                        />
                                    )}
                                    {(state === 'running' || state === 'next') && (
                                        <motion.div
                                            className="absolute inset-0 bg-blue-500/50"
                                            initial={{ x: '-100%' }}
                                            animate={{ x: '100%' }}
                                            transition={{ repeat: Infinity, duration: 1 }}
                                        />
                                    )}
                                </div>
                            )}
                        </React.Fragment>
                    );
                })}
            </div>
            <div className="mt-4 rounded border border-slate-700/60 bg-slate-900/40 px-3 py-2 text-xs text-slate-400">
                {latestCandidateEvent ? (
                    <span>
                        Candidate verification: <span className="font-semibold text-slate-200">{latestCandidateEvent.symbol}</span>{' '}
                        is in <span className="font-mono text-blue-300">{latestCandidateEvent.stage}</span>{' '}
                        with status <span className="font-mono text-slate-200">{latestCandidateEvent.status}</span>.
                    </span>
                ) : (
                    <span>No candidate-stage telemetry received yet.</span>
                )}
            </div>
        </div>
    );
}
