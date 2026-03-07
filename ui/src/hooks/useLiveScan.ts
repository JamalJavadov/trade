import { useState, useEffect, useRef, useCallback } from 'react';
import type {
    ScanSummaryDTO,
    ScanCandidateEventDTO,
    SymbolEvaluationRowDTO,
    ScanPhaseDTO,
    ScanChartsDTO
} from '../types/scan';
import { useErrorStore } from '../store/errorStore';
import { getScan, getScanCharts, getScanEvaluations } from '../api/scan';
import { buildApiUrl } from '../api/axiosSetup';

interface ScanProgress {
    evaluated: number;
    total: number;
    validCount: number;
    noTradeCount: number;
    eligibleCount?: number;
    dataErrorCount?: number;
}

interface BestRecommendation {
    symbol: string;
    side: string;
    finalScore: number;
}

function emptyCharts(): ScanChartsDTO {
    return {
        rrHist: [],
        confHist: [],
        scatterPoints: [],
        skipReasonBreakdown: {},
        biasBreakdown: {},
    };
}

function asMessageEvent(raw: unknown): MessageEvent<string> | null {
    if (!raw || typeof raw !== 'object' || !('data' in (raw as any))) return null;
    return raw as MessageEvent<string>;
}

export function useLiveScan(scanRunId: string | null) {
    const [status, setStatus] = useState<string>("CONNECTING");
    const [summary, setSummary] = useState<ScanSummaryDTO | null>(null);
    const [phases, setPhases] = useState<ScanPhaseDTO[]>([]);
    const [progress, setProgress] = useState<ScanProgress | null>(null);
    const [evaluations, setEvaluations] = useState<Map<string, SymbolEvaluationRowDTO>>(new Map());
    const [bestReco, setBestReco] = useState<BestRecommendation | null>(null);
    const [latestCandidateEvent, setLatestCandidateEvent] = useState<ScanCandidateEventDTO | null>(null);

    const [finalSummary, setFinalSummary] = useState<ScanSummaryDTO | null>(null);
    const [finalCharts, setFinalCharts] = useState<ScanChartsDTO | null>(null);
    const [finalEvals, setFinalEvals] = useState<SymbolEvaluationRowDTO[]>([]);
    const [lastUpdatedAt, setLastUpdatedAt] = useState<string | undefined>();

    const [sseConnected, setSseConnected] = useState<boolean>(false);
    const [reconnecting, setReconnecting] = useState<boolean>(false);
    const [polling, setPolling] = useState<boolean>(false);

    const statusRef = useRef<string>("CONNECTING");
    const eventSourceRef = useRef<EventSource | null>(null);
    const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const terminalRef = useRef<boolean>(false);
    const evalBufferRef = useRef<Map<string, SymbolEvaluationRowDTO>>(new Map());
    const flushTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const finalHydrateInFlightRef = useRef<Promise<void> | null>(null);
    const pollingInFlightRef = useRef(false);

    const updateStatus = useCallback((next: string) => {
        statusRef.current = next;
        setStatus(next);
    }, []);

    const flushEvaluations = useCallback(() => {
        if (evalBufferRef.current.size > 0) {
            setEvaluations(prev => {
                const next = new Map(prev);
                evalBufferRef.current.forEach((val, key) => next.set(key, val));
                return next;
            });
            evalBufferRef.current.clear();
        }
        flushTimeoutRef.current = null;
    }, []);

    const queueEvaluation = useCallback((ev: SymbolEvaluationRowDTO) => {
        evalBufferRef.current.set(ev.symbol, ev);
        if (!flushTimeoutRef.current) {
            flushTimeoutRef.current = setTimeout(flushEvaluations, 200);
        }
    }, [flushEvaluations]);

    const runFinalHydrate = useCallback(async (id: string) => {
        if (finalHydrateInFlightRef.current) {
            return finalHydrateInFlightRef.current;
        }

        const request = (async () => {
            try {
                const [summaryRes, chartsRes, evalsRes] = await Promise.all([
                    getScan(id),
                    getScanCharts(id),
                    getScanEvaluations(id, { limit: 300 })
                ]);

                const evalRows = evalsRes.content ?? [];
                setSummary(summaryRes);
                setPhases(summaryRes.phases ?? []);
                setProgress({
                    evaluated: summaryRes.evaluatedCount ?? evalRows.length,
                    total: summaryRes.topN ?? 300,
                    validCount: summaryRes.validCount ?? 0,
                    noTradeCount: summaryRes.noTradeCount ?? 0,
                    eligibleCount: summaryRes.eligibleCount ?? 0,
                    dataErrorCount: summaryRes.dataIntegrityFailureCount ?? 0,
                });

                setFinalSummary(summaryRes);
                setFinalCharts(chartsRes ?? emptyCharts());
                setFinalEvals(evalRows);
                setEvaluations(new Map(evalRows.map(row => [row.symbol, row])));
                setLastUpdatedAt(new Date().toLocaleTimeString());
            } catch (err) {
                useErrorStore.getState().addError({
                    timestamp: new Date().toISOString(),
                    path: `/api/v1/scans/${id}`,
                    errorCode: 'INTERNAL',
                    message: 'Failed to hydrate final scan details',
                    details: err instanceof Error ? err.stack || err.message : err,
                    traceId: `local-${Date.now()}`
                });
            } finally {
                finalHydrateInFlightRef.current = null;
            }
        })();

        finalHydrateInFlightRef.current = request;
        return request;
    }, []);

    useEffect(() => {
        if (!scanRunId) {
            updateStatus("CONNECTING");
            setSummary(null);
            setPhases([]);
            setProgress(null);
            setEvaluations(new Map());
            setBestReco(null);
            setLatestCandidateEvent(null);
            setFinalSummary(null);
            setFinalCharts(null);
            setFinalEvals([]);
            setSseConnected(false);
            setReconnecting(false);
            setPolling(false);
            useErrorStore.getState().setLiveScanContext(null);
            return;
        }

        updateStatus("CONNECTING");
        setSummary(null);
        setPhases([]);
        setProgress(null);
        setEvaluations(new Map());
        setBestReco(null);
        setLatestCandidateEvent(null);
        setFinalSummary(null);
        setFinalCharts(null);
        setFinalEvals([]);
        setLastUpdatedAt(undefined);
        setSseConnected(false);
        setReconnecting(false);
        setPolling(false);
        terminalRef.current = false;
        finalHydrateInFlightRef.current = null;
        pollingInFlightRef.current = false;

        let isSubscribed = true;
        let retryCount = 0;

        const clearReconnectTimer = () => {
            if (reconnectTimeoutRef.current) {
                clearTimeout(reconnectTimeoutRef.current);
                reconnectTimeoutRef.current = null;
            }
        };

        const closeSource = () => {
            if (eventSourceRef.current) {
                eventSourceRef.current.close();
                eventSourceRef.current = null;
            }
        };

        const connect = () => {
            if (!isSubscribed || terminalRef.current) return;

            const url = buildApiUrl(`/api/v1/scans/${scanRunId}/stream`);
            const es = new EventSource(url);
            eventSourceRef.current = es;

            es.onopen = () => {
                setSseConnected(true);
                setReconnecting(false);
                setPolling(false);
                retryCount = 0;
            };

            es.onerror = () => {
                closeSource();
                setSseConnected(false);

                if (!isSubscribed || terminalRef.current || statusRef.current === "FINISHED" || statusRef.current === "FAILED") {
                    return;
                }

                setReconnecting(true);
                retryCount += 1;

                useErrorStore.getState().addError({
                    timestamp: new Date().toISOString(),
                    path: url,
                    errorCode: 'SSE_DISCONNECT',
                    message: `Live stream disconnected (Attempt ${retryCount})`,
                    details: 'EventSource native onerror fired',
                    traceId: `local-${Date.now()}`
                });

                clearReconnectTimer();
                reconnectTimeoutRef.current = setTimeout(connect, 3000);
            };

            es.addEventListener("scan.started", (e: unknown) => {
                const event = asMessageEvent(e);
                if (!event) return;
                const data = JSON.parse(event.data);
                updateStatus("STARTED");
                setSummary(prev => ({
                    ...((prev || {}) as ScanSummaryDTO),
                    id: data.scanRunId,
                    startedAt: data.startedAt,
                    topN: data.topN,
                    intervalMinutes: data.intervalMinutes,
                    status: "STARTED",
                    phases: prev?.phases ?? []
                }));
            });

            es.addEventListener("phase.started", (e: unknown) => {
                const event = asMessageEvent(e);
                if (!event) return;
                const data = JSON.parse(event.data);
                setPhases(prev => {
                    const next = [...prev];
                    const existing = next.findIndex(p => p.phase === data.phase);
                    const newPhase: ScanPhaseDTO = {
                        phase: data.phase,
                        status: data.status,
                        startedAt: data.ts,
                        finishedAt: null,
                        durationMs: null,
                        meta: data.meta || {}
                    };
                    if (existing >= 0) {
                        next[existing] = { ...next[existing], ...newPhase };
                    } else {
                        next.push(newPhase);
                    }
                    return next;
                });
            });

            es.addEventListener("phase.finished", (e: unknown) => {
                const event = asMessageEvent(e);
                if (!event) return;
                const data = JSON.parse(event.data);
                setPhases(prev => {
                    const next = [...prev];
                    const existing = next.findIndex(p => p.phase === data.phase);
                    const newPhase: ScanPhaseDTO = {
                        phase: data.phase,
                        status: data.status,
                        startedAt: data.ts,
                        finishedAt: data.ts,
                        durationMs: null,
                        meta: data.meta || {}
                    };
                    if (existing >= 0) {
                        next[existing] = { ...next[existing], ...newPhase };
                    } else {
                        next.push(newPhase);
                    }
                    return next;
                });
            });

            es.addEventListener("progress", (e: unknown) => {
                const event = asMessageEvent(e);
                if (!event) return;
                const data = JSON.parse(event.data);
                setProgress({
                    evaluated: data.evaluated ?? 0,
                    total: data.total ?? 0,
                    validCount: data.validCount ?? 0,
                    noTradeCount: data.noTradeCount ?? 0,
                    eligibleCount: data.eligibleCount ?? 0,
                    dataErrorCount: data.dataErrorCount ?? 0,
                });
            });

            es.addEventListener("candidate.stage", (e: unknown) => {
                const event = asMessageEvent(e);
                if (!event) return;
                const data = JSON.parse(event.data);
                setLatestCandidateEvent({
                    symbol: data.symbol ?? '',
                    stage: data.stage ?? '',
                    seq: Number(data.seq ?? 0),
                    status: data.status ?? '',
                    ts: data.ts ?? '',
                    payload: data.payload ?? null,
                });
            });

            es.addEventListener("symbol.evaluated", (e: unknown) => {
                const event = asMessageEvent(e);
                if (!event) return;
                const data = JSON.parse(event.data) as SymbolEvaluationRowDTO;
                queueEvaluation(data);
            });

            es.addEventListener("recommendation.best", (e: unknown) => {
                const event = asMessageEvent(e);
                if (!event) return;
                const data = JSON.parse(event.data);
                setBestReco({
                    symbol: data.symbol,
                    side: data.side,
                    finalScore: data.finalScore
                });
            });

            es.addEventListener("scan.finished", (e: unknown) => {
                const event = asMessageEvent(e);
                if (!event) return;
                const data = JSON.parse(event.data);
                terminalRef.current = true;
                clearReconnectTimer();
                closeSource();
                updateStatus("FINISHED");
                setSummary(prev => prev ? { ...prev, status: "FINISHED", finishedAt: data.finishedAt } : prev);
                setSseConnected(false);
                setReconnecting(false);
                void runFinalHydrate(data.scanRunId || scanRunId);
            });

            es.addEventListener("scan.failed", (e: unknown) => {
                const event = asMessageEvent(e);
                if (!event) return;
                const data = JSON.parse(event.data);
                terminalRef.current = true;
                clearReconnectTimer();
                closeSource();
                updateStatus("FAILED");
                setSummary(prev => prev ? { ...prev, status: "FAILED", finishedAt: data.finishedAt, notes: data.error } : prev);
                setSseConnected(false);
                setReconnecting(false);
                void runFinalHydrate(data.scanRunId || scanRunId);
            });

            es.addEventListener("resync.required", () => {
                closeSource();
                setSseConnected(false);
            });
        };

        connect();

        return () => {
            isSubscribed = false;
            clearReconnectTimer();
            closeSource();
            if (flushTimeoutRef.current) {
                clearTimeout(flushTimeoutRef.current);
                flushTimeoutRef.current = null;
            }
        };
    }, [scanRunId, queueEvaluation, runFinalHydrate, updateStatus]);

    useEffect(() => {
        if (!scanRunId || status === "FINISHED" || status === "FAILED" || sseConnected) {
            setPolling(false);
            return;
        }

        setPolling(true);
        const fallbackInterval = setInterval(async () => {
            if (pollingInFlightRef.current) {
                return;
            }

            pollingInFlightRef.current = true;
            try {
                const latest = await getScan(scanRunId);
                setSummary(latest);
                setPhases(latest.phases ?? []);
                setProgress({
                    evaluated: latest.evaluatedCount ?? 0,
                    total: latest.topN ?? 300,
                    validCount: latest.validCount ?? 0,
                    noTradeCount: latest.noTradeCount ?? 0,
                    eligibleCount: latest.eligibleCount ?? 0,
                    dataErrorCount: latest.dataIntegrityFailureCount ?? 0,
                });

                if (latest.status === "FINISHED" || latest.status === "FAILED") {
                    terminalRef.current = true;
                    updateStatus(latest.status);
                    setReconnecting(false);
                    setSseConnected(false);
                    setPolling(false);
                    await runFinalHydrate(scanRunId);
                }
            } catch {
                // continue retrying
            } finally {
                pollingInFlightRef.current = false;
            }
        }, 4000);

        return () => {
            clearInterval(fallbackInterval);
            setPolling(false);
            pollingInFlightRef.current = false;
        };
    }, [scanRunId, sseConnected, status, runFinalHydrate, updateStatus]);

    useEffect(() => {
        if (!scanRunId) return;
        useErrorStore.getState().setLiveScanContext({
            scanRunId,
            statusPayload: {
                status,
                summary,
                progress,
                bestReco,
                latestCandidateEvent,
                finalSummaryStatus: finalSummary?.status ?? null,
                lastUpdatedAt: lastUpdatedAt ?? null,
            },
            updatedAt: new Date().toISOString(),
        });
    }, [bestReco, finalSummary?.status, lastUpdatedAt, latestCandidateEvent, progress, scanRunId, status, summary]);

    return {
        status,
        summary,
        phases: phases ?? [],
        progress,
        evaluations,
        bestReco,
        latestCandidateEvent,
        sseConnected,
        reconnecting,
        polling,
        finalSummary,
        finalCharts: finalCharts ?? (status === "FINISHED" || status === "FAILED" ? emptyCharts() : null),
        finalEvals: finalEvals ?? [],
        lastUpdatedAt
    };
}
