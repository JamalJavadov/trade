import { useState, useEffect, useRef, useCallback } from 'react';
import { getReplay } from '../api/scan';
import type { ScanReplayDTO, BestCandidateEventDTO } from '../types/scanReplay';
import { parseApiError } from '../utils/apiError';

export function useScanReplay(scanRunId: string | undefined) {
    const [data, setData] = useState<ScanReplayDTO | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const [isPlaying, setIsPlaying] = useState(false);
    const [progressMs, setProgressMs] = useState(0);
    const [totalDurationMs, setTotalDurationMs] = useState(0);
    const [playbackSpeed, setPlaybackSpeed] = useState(1);

    const rafRef = useRef<number | null>(null);
    const lastTickRef = useRef<number | null>(null);

    // Load data
    useEffect(() => {
        if (!scanRunId) return;
        const load = async () => {
            try {
                const replayData = await getReplay(scanRunId);
                setData(replayData);
                setError(null);

                // Calculate duration
                if (replayData.summary.startedAt) {
                    const start = new Date(replayData.summary.startedAt).getTime();
                    const end = replayData.summary.finishedAt
                        ? new Date(replayData.summary.finishedAt).getTime()
                        : Date.now();

                    setTotalDurationMs(Math.max(1000, end - start));
                }

            } catch (err: unknown) {
                const parsed = parseApiError(err);
                setError(parsed.message || "Failed to load replay");
            } finally {
                setLoading(false);
            }
        };
        load();
    }, [scanRunId]);

    // Playback engine
    const tick = useCallback((time: number) => {
        if (!lastTickRef.current) lastTickRef.current = time;
        const deltaMs = time - lastTickRef.current;
        lastTickRef.current = time;

        setProgressMs((prev) => {
            const next = prev + (deltaMs * playbackSpeed);
            if (next >= totalDurationMs) {
                setIsPlaying(false);
                return totalDurationMs;
            }
            return next;
        });

        if (isPlaying && progressMs < totalDurationMs) {
            rafRef.current = requestAnimationFrame(tick);
        }
    }, [isPlaying, playbackSpeed, progressMs, totalDurationMs]);

    useEffect(() => {
        if (isPlaying) {
            lastTickRef.current = performance.now();
            rafRef.current = requestAnimationFrame(tick);
        } else {
            if (rafRef.current) cancelAnimationFrame(rafRef.current);
            lastTickRef.current = null;
        }
        return () => {
            if (rafRef.current) cancelAnimationFrame(rafRef.current);
        };
    }, [isPlaying, tick]);

    // Controls
    const togglePlay = () => setIsPlaying(p => !p);

    const seek = (pct: number) => {
        setProgressMs(totalDurationMs * Math.max(0, Math.min(1, pct)));
    };

    const reset = () => {
        setIsPlaying(false);
        setProgressMs(0);
    };

    // Derived state for current time
    const currentTs = data?.summary.startedAt
        ? new Date(new Date(data.summary.startedAt).getTime() + progressMs).toISOString()
        : null;

    // Filter best candidates up to current time
    const visibleBestEvents = data?.bestCandidateEvents.filter(
        ev => new Date(ev.ts).getTime() <= new Date(currentTs || 0).getTime()
    ) || [];

    const currentBestCandidate: BestCandidateEventDTO | null =
        visibleBestEvents.length > 0 ? visibleBestEvents[visibleBestEvents.length - 1] : null;

    const visibleCandidateEvents = data?.candidateEvents.filter(
        ev => new Date(ev.ts).getTime() <= new Date(currentTs || 0).getTime()
    ) || [];

    // Filter phases up to current time
    const currentPhase = data?.phases.slice().reverse().find(
        p => new Date(p.startedAt).getTime() <= new Date(currentTs || 0).getTime()
    );

    return {
        data,
        loading,
        error,
        playback: {
            isPlaying,
            togglePlay,
            progressMs,
            totalDurationMs,
            seek,
            reset,
            speed: playbackSpeed,
            setSpeed: setPlaybackSpeed,
            progressPct: totalDurationMs > 0 ? (progressMs / totalDurationMs) : 0
        },
        derived: {
            currentTs,
            currentBestCandidate,
            currentPhase: currentPhase?.name || 'INITIALIZING',
            visibleCandidateEvents
        }
    };
}
