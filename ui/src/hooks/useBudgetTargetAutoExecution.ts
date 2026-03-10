import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { buildApiUrl } from '../api/axiosSetup';
import {
    getBudgetTargetAutoExecutionAuditReplay,
    getBudgetTargetAutoExecutionSessionDetail,
    getBudgetTargetAutoExecutionSessions,
    getBudgetTargetAutoExecutionState,
    getBudgetTargetAutoExecutionTimeline,
    getBudgetTargetAutoExecutionTradeDetail,
    getBudgetTargetAutoExecutionTrades,
    parseBudgetTargetSessionStreamEvent,
    startBudgetTargetAutoExecutionSession,
    stopBudgetTargetAutoExecutionSession,
    type BudgetTargetAutoExecutionStateDTO,
    type BudgetTargetEventTimelineItemDTO,
    type BudgetTargetEventTimelineResponseDTO,
    type BudgetTargetSessionAuditReplayDTO,
    type BudgetTargetSessionDetailDTO,
    type BudgetTargetSessionDTO,
    type BudgetTargetSessionSummaryDTO,
    type BudgetTargetTradeDetailDTO,
    type BudgetTargetTradeHistoryResponseDTO,
    type StartBudgetTargetAutoExecutionSessionRequestDTO,
    type StopBudgetTargetAutoExecutionSessionRequestDTO,
} from '../api/budgetTargetAutoExecutionApi';
import {
    getLiveTradingHealth,
    type BudgetTargetSyncHealthDTO,
    type LiveTradeExecutionDTO,
    type LiveTradingPreflightDTO,
} from '../api/liveTradingApi';
import { parseApiError, type ParsedApiError } from '../utils/apiError';

const HEALTH_SYMBOL = 'BTCUSDT';
const ACTIVE_POLL_INTERVAL_MS = 5_000;
const IDLE_POLL_INTERVAL_MS = 10_000;
const AUDIT_POLL_INTERVAL_MS = 4_000;
const STREAM_RECONNECT_DELAY_MS = 3_000;
const TIMELINE_LIMIT = 200;
const TRADE_HISTORY_LIMIT = 200;
const RESYNC_DEBOUNCE_MS = 750;
const CORE_RESYNC_DEBOUNCE_MS = 250;

const NON_TERMINAL_SESSION_STATUSES = new Set([
    'DRAFT',
    'ARMED',
    'RUNNING',
    'TARGET_REACHED',
    'STOPPING',
]);

const TERMINAL_SESSION_STATUSES = new Set([
    'STOPPED',
    'FAILED',
    'CANCELLED',
]);

const ACTIVE_EXECUTION_STATES = new Set([
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

const COMPLETED_EXECUTION_STATES = new Set([
    'CLOSED',
    'FAILED',
    'PREFLIGHT_REJECTED',
]);

const BLOCKER_EVENT_TYPES = new Set([
    'SESSION_BLOCKED',
    'ACTIVE_LIMIT_REACHED',
    'RECOMMENDATION_SKIPPED',
    'BANKROLL_EXHAUSTED',
    'SCAN_FAILED',
    'SESSION_EXECUTION_FAILURE',
]);

export interface BudgetTargetPrimaryBlockedReason {
    code: string | null;
    message: string;
    source: 'reconnecting' | 'health' | 'sync' | 'pending-scan' | 'event' | 'session' | 'none';
}

export interface UseBudgetTargetAutoExecutionOptions {
    includeAudit?: boolean;
    selectedSessionId?: string | null;
}

function byNewest(left: LiveTradeExecutionDTO, right: LiveTradeExecutionDTO): number {
    const leftTime = Date.parse(left.updatedAt ?? left.createdAt ?? '') || 0;
    const rightTime = Date.parse(right.updatedAt ?? right.createdAt ?? '') || 0;
    return rightTime - leftTime;
}

function sessionFromState(state: BudgetTargetAutoExecutionStateDTO | null): BudgetTargetSessionDTO | null {
    return state?.activeSession ?? state?.latestSession ?? null;
}

function isSessionActive(session: BudgetTargetSessionDTO | null): boolean {
    return Boolean(session && NON_TERMINAL_SESSION_STATUSES.has(session.status));
}

function resolveEventBlocker(state: BudgetTargetAutoExecutionStateDTO | null): BudgetTargetPrimaryBlockedReason | null {
    if (!state?.events?.length) {
        return null;
    }

    const event = state.events.find((item) => BLOCKER_EVENT_TYPES.has(item.eventType));
    if (!event) {
        return null;
    }

    return {
        code: event.reasonCode,
        message: event.message ?? event.notes ?? event.eventType,
        source: 'event',
    };
}

function resolveStateBlocker(state: BudgetTargetAutoExecutionStateDTO | null): BudgetTargetPrimaryBlockedReason | null {
    if (!state?.primaryBlockedReasonCode && !state?.primaryBlockedReasonMessage) {
        return null;
    }

    const source = state.primaryBlockedReasonSource;
    return {
        code: state.primaryBlockedReasonCode,
        message: state.primaryBlockedReasonMessage ?? 'Session reported a blocker.',
        source: source === 'pending-scan'
            ? 'pending-scan'
            : source === 'event'
                ? 'event'
                : 'session',
    };
}

function resolveHealthBlocker(health: LiveTradingPreflightDTO | null): BudgetTargetPrimaryBlockedReason | null {
    if (!health || health.executable) {
        return null;
    }

    const firstBlocked = health.blockedReasons[0];
    return {
        code: health.summary.primaryBlockerCode ?? firstBlocked?.code ?? health.binance.blockerCode ?? null,
        message: health.summary.primaryBlockerMessage
            ?? firstBlocked?.message
            ?? health.binance.blockerMessage
            ?? 'Live trading health is blocking new orders.',
        source: 'health',
    };
}

function resolveSyncBlocker(syncHealth: BudgetTargetSyncHealthDTO | null): BudgetTargetPrimaryBlockedReason | null {
    if (!syncHealth?.gateNewTrades) {
        return null;
    }
    return {
        code: syncHealth.gateReasonCode ?? syncHealth.latestErrorCode ?? null,
        message: syncHealth.gateReasonMessage
            ?? syncHealth.latestErrorMessage
            ?? 'Session sync health is blocking new trades.',
        source: 'sync',
    };
}

function toNumber(value: number | null | undefined): number | null {
    return value == null || Number.isNaN(value) ? null : value;
}

function resolveAuditSessionId(
    selectedSessionId: string | null | undefined,
    state: BudgetTargetAutoExecutionStateDTO | null,
    sessions: BudgetTargetSessionSummaryDTO[],
): string | null {
    if (selectedSessionId) {
        return selectedSessionId;
    }
    const currentSession = sessionFromState(state);
    if (currentSession?.id) {
        return currentSession.id;
    }
    return sessions[0]?.id ?? null;
}

function updateSessionList(
    sessions: BudgetTargetSessionSummaryDTO[],
    nextSummary: BudgetTargetSessionSummaryDTO | null,
): BudgetTargetSessionSummaryDTO[] {
    if (!nextSummary?.id) {
        return sessions;
    }

    const next = sessions.filter((item) => item.id !== nextSummary.id);
    next.unshift(nextSummary);
    return next;
}

function prependTimelineItem(
    timeline: BudgetTargetEventTimelineResponseDTO | null,
    nextItem: BudgetTargetEventTimelineItemDTO | null,
): BudgetTargetEventTimelineResponseDTO | null {
    if (!timeline || !nextItem?.id) {
        return timeline;
    }

    const items = [nextItem, ...timeline.items.filter((item) => item.id !== nextItem.id)].slice(0, TIMELINE_LIMIT);
    return {
        ...timeline,
        items,
        total: Math.max(timeline.total + 1, items.length),
        filteredCount: Math.max(timeline.filteredCount + 1, items.length),
    };
}

function safeParseEventData(raw: string): unknown {
    try {
        return JSON.parse(raw);
    } catch {
        return null;
    }
}

export function useBudgetTargetAutoExecution(options: UseBudgetTargetAutoExecutionOptions = {}) {
    const includeAudit = options.includeAudit ?? false;
    const selectedSessionId = options.selectedSessionId ?? null;

    const [state, setState] = useState<BudgetTargetAutoExecutionStateDTO | null>(null);
    const [health, setHealth] = useState<LiveTradingPreflightDTO | null>(null);
    const [sessions, setSessions] = useState<BudgetTargetSessionSummaryDTO[]>([]);
    const [resolvedSessionId, setResolvedSessionId] = useState<string | null>(null);
    const [sessionDetail, setSessionDetail] = useState<BudgetTargetSessionDetailDTO | null>(null);
    const [timeline, setTimeline] = useState<BudgetTargetEventTimelineResponseDTO | null>(null);
    const [tradeHistory, setTradeHistory] = useState<BudgetTargetTradeHistoryResponseDTO | null>(null);
    const [tradeDetail, setTradeDetail] = useState<BudgetTargetTradeDetailDTO | null>(null);
    const [tradeDetailLoading, setTradeDetailLoading] = useState(false);
    const [auditReplay, setAuditReplay] = useState<BudgetTargetSessionAuditReplayDTO | null>(null);
    const [auditReplayLoading, setAuditReplayLoading] = useState(false);

    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [auditLoading, setAuditLoading] = useState(includeAudit);
    const [auditRefreshing, setAuditRefreshing] = useState(false);
    const [updating, setUpdating] = useState(false);
    const [reconnecting, setReconnecting] = useState(false);
    const [error, setError] = useState<ParsedApiError | null>(null);
    const [lastUpdatedAt, setLastUpdatedAt] = useState<string | null>(null);
    const [streamConnected, setStreamConnected] = useState(false);
    const [streamReconnecting, setStreamReconnecting] = useState(false);
    const [streamPolling, setStreamPolling] = useState(false);

    const coreRequestRef = useRef<Promise<void> | null>(null);
    const auditRequestRef = useRef<Promise<void> | null>(null);
    const stateRef = useRef<BudgetTargetAutoExecutionStateDTO | null>(null);
    const sessionsRef = useRef<BudgetTargetSessionSummaryDTO[]>([]);
    const hasLoadedCoreRef = useRef(false);
    const eventSourceRef = useRef<EventSource | null>(null);
    const reconnectTimeoutRef = useRef<number | null>(null);
    const resyncTimeoutRef = useRef<number | null>(null);
    const coreResyncTimeoutRef = useRef<number | null>(null);
    const lastEventIdRef = useRef(0);

    useEffect(() => {
        stateRef.current = state;
    }, [state]);

    useEffect(() => {
        sessionsRef.current = sessions;
    }, [sessions]);

    const refreshCore = useCallback(async (background = hasLoadedCoreRef.current) => {
        if (coreRequestRef.current) {
            return coreRequestRef.current;
        }

        if (background) {
            setRefreshing(true);
        } else {
            setLoading(true);
        }

        const request = (async () => {
            const results = await Promise.allSettled([
                getBudgetTargetAutoExecutionState(),
                getLiveTradingHealth(HEALTH_SYMBOL),
            ]);

            const [stateResult, healthResult] = results;
            const failures: ParsedApiError[] = [];
            let successfulSnapshot = false;

            if (stateResult.status === 'fulfilled') {
                setState(stateResult.value);
                successfulSnapshot = true;
            } else {
                failures.push(parseApiError(stateResult.reason));
            }

            if (healthResult.status === 'fulfilled') {
                setHealth(healthResult.value);
                successfulSnapshot = true;
            } else {
                failures.push(parseApiError(healthResult.reason));
            }

            if (successfulSnapshot) {
                hasLoadedCoreRef.current = true;
                setLastUpdatedAt(
                    (stateResult.status === 'fulfilled' ? stateResult.value.serverTime : null)
                    ?? (healthResult.status === 'fulfilled' ? healthResult.value.checkedAt : null)
                    ?? new Date().toISOString(),
                );
            }

            if (failures.length > 0) {
                setError(failures[0]);
                setReconnecting(hasLoadedCoreRef.current);
            } else {
                setError(null);
                setReconnecting(false);
            }

            if (!hasLoadedCoreRef.current && failures.length > 0) {
                setState(null);
                setHealth(null);
            }

            setLoading(false);
            setRefreshing(false);
            coreRequestRef.current = null;
        })().catch((unhandledError) => {
            setError(parseApiError(unhandledError));
            setLoading(false);
            setRefreshing(false);
            coreRequestRef.current = null;
        });

        coreRequestRef.current = request;
        return request;
    }, []);

    const refreshAudit = useCallback(async (background = true) => {
        if (!includeAudit) {
            return;
        }
        if (auditRequestRef.current) {
            return auditRequestRef.current;
        }

        if (background) {
            setAuditRefreshing(true);
        } else {
            setAuditLoading(true);
        }

        const request = (async () => {
            const sessionsResult = await Promise.allSettled([
                getBudgetTargetAutoExecutionSessions(20, 0),
            ]);

            const [listResult] = sessionsResult;
            let nextSessions = sessionsRef.current;
            const failures: ParsedApiError[] = [];

            if (listResult.status === 'fulfilled') {
                nextSessions = listResult.value;
                setSessions(nextSessions);
            } else {
                failures.push(parseApiError(listResult.reason));
            }

            const nextResolvedSessionId = resolveAuditSessionId(selectedSessionId, stateRef.current, nextSessions);
            setResolvedSessionId(nextResolvedSessionId);

            if (!nextResolvedSessionId) {
                setSessionDetail(null);
                setTimeline(null);
                setTradeHistory(null);
                setTradeDetail(null);
                setAuditReplay(null);
                if (failures.length > 0) {
                    setError(failures[0]);
                }
                setAuditLoading(false);
                setAuditRefreshing(false);
                auditRequestRef.current = null;
                return;
            }

            const [detailResult, timelineResult, tradesResult] = await Promise.allSettled([
                getBudgetTargetAutoExecutionSessionDetail(nextResolvedSessionId),
                getBudgetTargetAutoExecutionTimeline(nextResolvedSessionId, { limit: TIMELINE_LIMIT }),
                getBudgetTargetAutoExecutionTrades(nextResolvedSessionId, { limit: TRADE_HISTORY_LIMIT }),
            ]);

            if (detailResult.status === 'fulfilled') {
                setSessionDetail(detailResult.value);
                setSessions((current) => updateSessionList(current, detailResult.value.summary));
            } else {
                failures.push(parseApiError(detailResult.reason));
            }

            if (timelineResult.status === 'fulfilled') {
                setTimeline(timelineResult.value);
            } else {
                failures.push(parseApiError(timelineResult.reason));
            }

            if (tradesResult.status === 'fulfilled') {
                setTradeHistory(tradesResult.value);
            } else {
                failures.push(parseApiError(tradesResult.reason));
            }

            if (failures.length > 0) {
                setError((current) => current ?? failures[0]);
            }

            setAuditLoading(false);
            setAuditRefreshing(false);
            auditRequestRef.current = null;
        })().catch((unhandledError) => {
            setError(parseApiError(unhandledError));
            setAuditLoading(false);
            setAuditRefreshing(false);
            auditRequestRef.current = null;
        });

        auditRequestRef.current = request;
        return request;
    }, [includeAudit, selectedSessionId]);

    const scheduleAuditRefresh = useCallback(() => {
        if (!includeAudit) {
            return;
        }
        if (resyncTimeoutRef.current) {
            window.clearTimeout(resyncTimeoutRef.current);
        }
        resyncTimeoutRef.current = window.setTimeout(() => {
            void refreshAudit(true);
            resyncTimeoutRef.current = null;
        }, RESYNC_DEBOUNCE_MS);
    }, [includeAudit, refreshAudit]);

    const scheduleCoreRefresh = useCallback(() => {
        if (coreResyncTimeoutRef.current) {
            window.clearTimeout(coreResyncTimeoutRef.current);
        }
        coreResyncTimeoutRef.current = window.setTimeout(() => {
            void refreshCore(true);
            coreResyncTimeoutRef.current = null;
        }, CORE_RESYNC_DEBOUNCE_MS);
    }, [refreshCore]);

    const refresh = useCallback(async (background = hasLoadedCoreRef.current) => {
        await refreshCore(background);
        if (includeAudit) {
            await refreshAudit(background);
        }
    }, [includeAudit, refreshAudit, refreshCore]);

    useEffect(() => {
        void refresh(false);
    }, [refresh]);

    const session = useMemo(() => sessionFromState(state), [state]);
    const sessionIsActive = useMemo(() => isSessionActive(session), [session]);
    const syncHealth = useMemo(
        () => session?.syncHealth ?? state?.syncHealth ?? null,
        [session, state],
    );

    useEffect(() => {
        const intervalId = window.setInterval(() => {
            void refreshCore(true);
        }, sessionIsActive ? ACTIVE_POLL_INTERVAL_MS : IDLE_POLL_INTERVAL_MS);

        return () => {
            window.clearInterval(intervalId);
        };
    }, [refreshCore, sessionIsActive]);

    useEffect(() => {
        if (!includeAudit || !hasLoadedCoreRef.current) {
            return;
        }
        void refreshAudit(true);
    }, [includeAudit, refreshAudit, selectedSessionId, state?.activeSession?.id, state?.latestSession?.id]);

    const syncHealthAfterMutation = useCallback(async () => {
        try {
            const nextHealth = await getLiveTradingHealth(HEALTH_SYMBOL);
            setHealth(nextHealth);
            setError(null);
            setReconnecting(false);
        } catch (nextError) {
            setError(parseApiError(nextError));
            setReconnecting(true);
        }
    }, []);

    const startSession = useCallback(async (payload: StartBudgetTargetAutoExecutionSessionRequestDTO) => {
        setUpdating(true);
        try {
            const nextState = await startBudgetTargetAutoExecutionSession(payload);
            setState(nextState);
            setLastUpdatedAt(nextState.serverTime ?? new Date().toISOString());
            hasLoadedCoreRef.current = true;
            await syncHealthAfterMutation();
            if (includeAudit) {
                await refreshAudit(false);
            }
            return nextState;
        } catch (nextError) {
            const parsed = parseApiError(nextError);
            setError(parsed);
            throw nextError;
        } finally {
            setUpdating(false);
        }
    }, [includeAudit, refreshAudit, syncHealthAfterMutation]);

    const stopSession = useCallback(async (payload: StopBudgetTargetAutoExecutionSessionRequestDTO = {}) => {
        setUpdating(true);
        try {
            const nextState = await stopBudgetTargetAutoExecutionSession(payload);
            setState(nextState);
            setLastUpdatedAt(nextState.serverTime ?? new Date().toISOString());
            hasLoadedCoreRef.current = true;
            await syncHealthAfterMutation();
            if (includeAudit) {
                await refreshAudit(false);
            }
            return nextState;
        } catch (nextError) {
            const parsed = parseApiError(nextError);
            setError(parsed);
            throw nextError;
        } finally {
            setUpdating(false);
        }
    }, [includeAudit, refreshAudit, syncHealthAfterMutation]);

    const activeOrders = useMemo(() => {
        if (!state?.orders?.length) {
            return [];
        }
        return state.orders
            .filter((order) => ACTIVE_EXECUTION_STATES.has(order.executionState))
            .sort(byNewest);
    }, [state]);

    const completedOrders = useMemo(() => {
        if (!state?.orders?.length) {
            return [];
        }
        return state.orders
            .filter((order) => COMPLETED_EXECUTION_STATES.has(order.executionState))
            .sort(byNewest);
    }, [state]);

    const targetProfitUsdt = toNumber(session?.targetProfitUsdt ?? state?.config.defaultTargetProfitUsdt);
    const realizedNetPnlUsdt = toNumber(session?.realizedNetPnlUsdt);

    const remainingToTargetUsdt = useMemo(() => {
        if (targetProfitUsdt == null) {
            return null;
        }
        return Math.max(targetProfitUsdt - (realizedNetPnlUsdt ?? 0), 0);
    }, [realizedNetPnlUsdt, targetProfitUsdt]);

    const targetProgressPct = useMemo(() => {
        if (targetProfitUsdt == null || targetProfitUsdt <= 0) {
            return 0;
        }
        const realized = realizedNetPnlUsdt ?? 0;
        return Math.max(0, Math.min((realized / targetProfitUsdt) * 100, 100));
    }, [realizedNetPnlUsdt, targetProfitUsdt]);

    const primaryBlockedReason = useMemo<BudgetTargetPrimaryBlockedReason>(() => {
        if (reconnecting) {
            return {
                code: error?.errorCode ?? null,
                message: error?.message
                    ? `Reconnecting to backend state. Showing last confirmed data. ${error.message}`
                    : 'Reconnecting to backend state. Showing last confirmed data.',
                source: 'reconnecting',
            };
        }

        const syncBlocker = resolveSyncBlocker(syncHealth);
        if (syncBlocker) {
            return syncBlocker;
        }

        const stateBlocker = resolveStateBlocker(state);
        if (stateBlocker) {
            return stateBlocker;
        }

        if (session?.pendingScanRunId) {
            return {
                code: 'PENDING_SCAN',
                message: `Waiting for session-owned scan ${session.pendingScanRunId} to finish before opening a new trade.`,
                source: 'pending-scan',
            };
        }

        if (session?.stopReasonMessage || session?.lastErrorMessage) {
            return {
                code: session.lastErrorCode ?? session.stopReason ?? null,
                message: session.stopReasonMessage ?? session.lastErrorMessage ?? 'Session reported a blocker.',
                source: 'session',
            };
        }

        const eventBlocker = resolveEventBlocker(state);
        if (eventBlocker) {
            return eventBlocker;
        }

        const healthBlocker = resolveHealthBlocker(health);
        if (healthBlocker) {
            return healthBlocker;
        }

        return {
            code: null,
            message: 'No blocker reported.',
            source: 'none',
        };
    }, [error, health, reconnecting, session, state, syncHealth]);

    const loadTradeDetail = useCallback(async (executionId: string) => {
        if (!resolvedSessionId) {
            return null;
        }
        setTradeDetailLoading(true);
        try {
            const detail = await getBudgetTargetAutoExecutionTradeDetail(resolvedSessionId, executionId);
            setTradeDetail(detail);
            return detail;
        } catch (nextError) {
            setError(parseApiError(nextError));
            throw nextError;
        } finally {
            setTradeDetailLoading(false);
        }
    }, [resolvedSessionId]);

    const clearTradeDetail = useCallback(() => {
        setTradeDetail(null);
    }, []);

    const loadAuditReplay = useCallback(async () => {
        if (!resolvedSessionId) {
            return null;
        }
        setAuditReplayLoading(true);
        try {
            const replay = await getBudgetTargetAutoExecutionAuditReplay(resolvedSessionId);
            setAuditReplay(replay);
            return replay;
        } catch (nextError) {
            setError(parseApiError(nextError));
            throw nextError;
        } finally {
            setAuditReplayLoading(false);
        }
    }, [resolvedSessionId]);

    useEffect(() => {
        setTradeDetail(null);
        setAuditReplay(null);
        lastEventIdRef.current = 0;
    }, [resolvedSessionId]);

    useEffect(() => {
        if (!includeAudit || !resolvedSessionId) {
            setStreamConnected(false);
            setStreamReconnecting(false);
            setStreamPolling(false);
            return;
        }

        const status = sessionDetail?.summary?.status;
        if (status && TERMINAL_SESSION_STATUSES.has(status)) {
            setStreamConnected(false);
            setStreamReconnecting(false);
            setStreamPolling(false);
            return;
        }

        let isSubscribed = true;

        const clearReconnectTimer = () => {
            if (reconnectTimeoutRef.current) {
                window.clearTimeout(reconnectTimeoutRef.current);
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
            if (!isSubscribed) {
                return;
            }

            const query = lastEventIdRef.current > 0 ? `?lastEventId=${encodeURIComponent(String(lastEventIdRef.current))}` : '';
            const source = new EventSource(buildApiUrl(
                `/api/v1/budget-target-auto-execution/sessions/${resolvedSessionId}/stream${query}`,
            ));
            eventSourceRef.current = source;

            source.onopen = () => {
                setStreamConnected(true);
                setStreamReconnecting(false);
                setStreamPolling(false);
            };

            source.onerror = () => {
                closeSource();
                setStreamConnected(false);
                if (!isSubscribed) {
                    return;
                }
                setStreamReconnecting(true);
                clearReconnectTimer();
                reconnectTimeoutRef.current = window.setTimeout(connect, STREAM_RECONNECT_DELAY_MS);
            };

            source.addEventListener('session.update', (rawEvent) => {
                if (!(rawEvent instanceof MessageEvent)) {
                    return;
                }
                const payload = parseBudgetTargetSessionStreamEvent(safeParseEventData(rawEvent.data));
                lastEventIdRef.current = payload.eventId || Number(rawEvent.lastEventId || 0) || lastEventIdRef.current;
                if (payload.summary) {
                    const nextSummary = payload.summary;
                    setSessionDetail((current) => current == null
                        ? current
                        : {
                            ...current,
                            summary: nextSummary,
                            latestCriticalError: nextSummary.mostRecentCriticalError ?? current.latestCriticalError,
                            lastEventAt: payload.timelineItem?.eventTs ?? current.lastEventAt,
                        });
                    setSessions((current) => updateSessionList(current, nextSummary));
                }
                if (payload.timelineItem) {
                    setTimeline((current) => prependTimelineItem(current, payload.timelineItem));
                    setLastUpdatedAt(payload.timelineItem.eventTs ?? new Date().toISOString());
                }
                scheduleCoreRefresh();
                scheduleAuditRefresh();
            });

            source.addEventListener('heartbeat', () => {
                setLastUpdatedAt(new Date().toISOString());
            });

            source.addEventListener('resync.required', () => {
                closeSource();
                setStreamConnected(false);
                setStreamReconnecting(false);
                setStreamPolling(true);
                void refreshCore(true);
                void refreshAudit(true);
            });
        };

        connect();

        return () => {
            isSubscribed = false;
            clearReconnectTimer();
            closeSource();
        };
    }, [includeAudit, refreshAudit, refreshCore, resolvedSessionId, scheduleAuditRefresh, scheduleCoreRefresh, sessionDetail?.summary?.status]);

    useEffect(() => {
        if (!includeAudit || !resolvedSessionId) {
            return;
        }
        if (streamConnected) {
            setStreamPolling(false);
            return;
        }
        if (sessionDetail?.summary?.status && TERMINAL_SESSION_STATUSES.has(sessionDetail.summary.status)) {
            setStreamPolling(false);
            return;
        }

        setStreamPolling(true);
        const intervalId = window.setInterval(() => {
            void refreshAudit(true);
        }, AUDIT_POLL_INTERVAL_MS);

        return () => {
            window.clearInterval(intervalId);
            setStreamPolling(false);
        };
    }, [includeAudit, refreshAudit, resolvedSessionId, sessionDetail?.summary?.status, streamConnected]);

    useEffect(() => () => {
        if (eventSourceRef.current) {
            eventSourceRef.current.close();
        }
        if (reconnectTimeoutRef.current) {
            window.clearTimeout(reconnectTimeoutRef.current);
        }
        if (resyncTimeoutRef.current) {
            window.clearTimeout(resyncTimeoutRef.current);
        }
        if (coreResyncTimeoutRef.current) {
            window.clearTimeout(coreResyncTimeoutRef.current);
        }
    }, []);

    const auditTransportMode = !includeAudit
        ? 'disabled'
        : streamConnected
            ? 'sse'
            : streamReconnecting
                ? 'reconnecting'
                : streamPolling
                    ? 'polling'
                    : 'rest';

    return {
        state,
        session,
        health,
        syncHealth,
        sessions,
        resolvedSessionId,
        sessionDetail,
        timeline,
        tradeHistory,
        tradeDetail,
        tradeDetailLoading,
        auditReplay,
        auditReplayLoading,
        loading,
        refreshing,
        auditLoading,
        auditRefreshing,
        updating,
        reconnecting,
        error,
        lastUpdatedAt,
        sessionIsActive,
        activeOrders,
        completedOrders,
        targetProgressPct,
        remainingToTargetUsdt,
        primaryBlockedReason,
        streamConnected,
        streamReconnecting,
        streamPolling,
        auditTransportMode,
        refresh,
        refreshAudit,
        startSession,
        stopSession,
        loadTradeDetail,
        clearTradeDetail,
        loadAuditReplay,
    };
}
