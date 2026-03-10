import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ParsedApiError } from '../utils/apiError';
import { parseApiError } from '../utils/apiError';
import {
    executeRecommendationLive,
    getLiveExecution,
    getRecommendationExecutionPreflight,
    listLiveExecutions,
    type LiveTradeExecutionDTO,
    type LiveTradeExecutionRequestDTO,
    type LiveTradingPreflightDTO,
} from '../api/liveTradingApi';

const POLLING_STATES = new Set([
    'CREATED',
    'PREFLIGHT_VALIDATING',
    'ENTRY_SUBMITTING',
    'ENTRY_SUBMITTED',
    'ENTRY_FILLED',
    'PROTECTION_SUBMITTING',
    'PROTECTION_ACTIVE',
    'ACTIVE',
    'CLOSING',
    'RECONCILING',
]);

interface RecommendationExecutionState {
    preflight: LiveTradingPreflightDTO | null;
    execution: LiveTradeExecutionDTO | null;
    history: LiveTradeExecutionDTO[];
    loading: boolean;
    preflightLoading: boolean;
    historyLoading: boolean;
    executing: boolean;
    actionError: ParsedApiError | null;
    refresh: () => Promise<void>;
    executeLive: (request: LiveTradeExecutionRequestDTO) => Promise<LiveTradeExecutionDTO>;
    clearActionError: () => void;
}

function mergeExecution(
    history: LiveTradeExecutionDTO[],
    nextExecution: LiveTradeExecutionDTO,
): LiveTradeExecutionDTO[] {
    const withoutCurrent = history.filter((item) => item.id !== nextExecution.id);
    return [nextExecution, ...withoutCurrent];
}

export function useRecommendationExecution(
    recommendationId: string | undefined,
    enabled = true,
): RecommendationExecutionState {
    const [preflight, setPreflight] = useState<LiveTradingPreflightDTO | null>(null);
    const [execution, setExecution] = useState<LiveTradeExecutionDTO | null>(null);
    const [history, setHistory] = useState<LiveTradeExecutionDTO[]>([]);
    const [preflightLoading, setPreflightLoading] = useState(true);
    const [historyLoading, setHistoryLoading] = useState(true);
    const [executing, setExecuting] = useState(false);
    const [actionError, setActionError] = useState<ParsedApiError | null>(null);
    const historyRequestRef = useRef<Promise<void> | null>(null);

    const loadPreflight = useCallback(async () => {
        if (!recommendationId || !enabled) {
            setPreflight(null);
            setPreflightLoading(false);
            return;
        }
        setPreflightLoading(true);
        try {
            const nextPreflight = await getRecommendationExecutionPreflight(recommendationId);
            setPreflight(nextPreflight);
        } finally {
            setPreflightLoading(false);
        }
    }, [enabled, recommendationId]);

    const loadHistory = useCallback(async () => {
        if (!recommendationId || !enabled) {
            setHistory([]);
            setExecution(null);
            setHistoryLoading(false);
            return;
        }
        if (historyRequestRef.current) {
            return historyRequestRef.current;
        }
        setHistoryLoading(true);
        const request = (async () => {
            try {
                const executions = await listLiveExecutions({ recommendationId, limit: 10 });
                setHistory(executions);
                setExecution((current) => {
                    if (current) {
                        return executions.find((item) => item.id === current.id) ?? current;
                    }
                    return executions[0] ?? null;
                });
            } finally {
                setHistoryLoading(false);
                historyRequestRef.current = null;
            }
        })();
        historyRequestRef.current = request;
        return request;
    }, [enabled, recommendationId]);

    const refresh = useCallback(async () => {
        await Promise.all([loadPreflight(), loadHistory()]);
    }, [loadHistory, loadPreflight]);

    useEffect(() => {
        setActionError(null);
        setPreflight(null);
        setExecution(null);
        setHistory([]);
        setPreflightLoading(enabled && Boolean(recommendationId));
        setHistoryLoading(enabled && Boolean(recommendationId));
        void refresh();
    }, [enabled, recommendationId, refresh]);

    useEffect(() => {
        if (!execution || !POLLING_STATES.has(execution.executionState)) {
            return;
        }

        const intervalId = window.setInterval(() => {
            void (async () => {
                try {
                    const fresh = await getLiveExecution(execution.id);
                    setExecution(fresh);
                    setHistory((current) => mergeExecution(current, fresh));
                } catch {
                    // Keep last known state and allow manual refresh.
                }
            })();
        }, 3_000);

        return () => {
            window.clearInterval(intervalId);
        };
    }, [execution]);

    const executeLive = useCallback(async (request: LiveTradeExecutionRequestDTO) => {
        if (!recommendationId) {
            throw new Error('Recommendation ID is required.');
        }
        setExecuting(true);
        setActionError(null);
        try {
            const nextExecution = await executeRecommendationLive(recommendationId, request);
            setExecution(nextExecution);
            setHistory((current) => mergeExecution(current, nextExecution));
            await loadPreflight();
            return nextExecution;
        } catch (error) {
            setActionError(parseApiError(error));
            throw error;
        } finally {
            setExecuting(false);
        }
    }, [loadPreflight, recommendationId]);

    const loading = useMemo(
        () => preflightLoading || historyLoading,
        [historyLoading, preflightLoading],
    );

    return {
        preflight,
        execution,
        history,
        loading,
        preflightLoading,
        historyLoading,
        executing,
        actionError,
        refresh,
        executeLive,
        clearActionError: () => setActionError(null),
    };
}
