import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Banner } from '../components/Banner';
import { useBudgetTargetAutoExecution, type BudgetTargetPrimaryBlockedReason } from '../hooks/useBudgetTargetAutoExecution';
import { usePermissions } from '../hooks/usePermissions';
import { useToastStore } from '../store/toastStore';
import type {
    BudgetTargetEventTimelineItemDTO,
    BudgetTargetTradeHistoryItemDTO,
} from '../api/budgetTargetAutoExecutionApi';
import type { LiveTradeExecutionDTO } from '../api/liveTradingApi';

function formatMoney(value: number | null | undefined): string {
    if (value == null || Number.isNaN(value)) {
        return 'n/a';
    }
    return `${value.toFixed(2)} USDT`;
}

function formatSignedMoney(value: number | null | undefined): string {
    if (value == null || Number.isNaN(value)) {
        return 'n/a';
    }
    return `${value >= 0 ? '+' : ''}${value.toFixed(2)} USDT`;
}

function formatInstant(value: string | null | undefined): string {
    if (!value) {
        return 'n/a';
    }
    return new Date(value).toLocaleString();
}

function formatEditable(value: number | null | undefined): string {
    if (value == null || Number.isNaN(value)) {
        return '';
    }
    return value.toString();
}

function formatMarketValue(value: number | null | undefined): string {
    if (value == null || Number.isNaN(value)) {
        return 'n/a';
    }
    const magnitude = Math.abs(value);
    const decimals = magnitude >= 1000 ? 2 : magnitude >= 1 ? 4 : 6;
    return value.toFixed(decimals).replace(/\.?0+$/, '');
}

function jsonText(value: unknown): string {
    try {
        return JSON.stringify(value ?? {}, null, 2);
    } catch {
        return '{}';
    }
}

function statusTone(status: string | null | undefined): string {
    if (!status) {
        return 'border-slate-700 bg-slate-900/50 text-slate-200';
    }
    if (status === 'RUNNING') {
        return 'border-emerald-700/50 bg-emerald-900/20 text-emerald-200';
    }
    if (status === 'STOPPING' || status === 'TARGET_REACHED' || status === 'ARMED') {
        return 'border-amber-700/50 bg-amber-900/20 text-amber-200';
    }
    if (status === 'FAILED') {
        return 'border-rose-700/50 bg-rose-900/20 text-rose-200';
    }
    return 'border-sky-700/50 bg-sky-900/20 text-sky-200';
}

function blockerTone(reason: BudgetTargetPrimaryBlockedReason): string {
    if (reason.source === 'none') {
        return 'border-slate-700 bg-slate-900/40 text-slate-200';
    }
    if (reason.source === 'reconnecting') {
        return 'border-amber-700/50 bg-amber-900/20 text-amber-100';
    }
    if (reason.source === 'health' || reason.source === 'sync') {
        return 'border-rose-700/50 bg-rose-900/20 text-rose-100';
    }
    return 'border-sky-700/50 bg-sky-900/20 text-sky-100';
}

function syncTone(status: string | null | undefined): string {
    if (!status || status === 'IDLE') {
        return 'border-slate-700 bg-slate-900/40 text-slate-200';
    }
    if (status === 'HEALTHY') {
        return 'border-emerald-700/50 bg-emerald-900/20 text-emerald-200';
    }
    if (status === 'STALE' || status === 'PENDING_FIRST_SYNC') {
        return 'border-amber-700/50 bg-amber-900/20 text-amber-200';
    }
    return 'border-rose-700/50 bg-rose-900/20 text-rose-200';
}

function transportTone(mode: string): string {
    if (mode === 'sse') {
        return 'border-emerald-700/50 bg-emerald-900/20 text-emerald-200';
    }
    if (mode === 'polling' || mode === 'reconnecting') {
        return 'border-amber-700/50 bg-amber-900/20 text-amber-200';
    }
    return 'border-slate-700 bg-slate-900/40 text-slate-200';
}

function severityTone(severity: string | null | undefined): string {
    if (severity === 'ERROR') {
        return 'text-rose-300';
    }
    if (severity === 'WARN') {
        return 'text-amber-300';
    }
    return 'text-slate-200';
}

function formatTimelineSummary(event: BudgetTargetEventTimelineItemDTO): string {
    const pairs = Object.entries(event.summaryPayload ?? {});
    if (pairs.length === 0) {
        return event.reasonCode ?? event.status ?? 'n/a';
    }
    return pairs
        .slice(0, 3)
        .map(([key, value]) => `${key}=${typeof value === 'object' ? '[json]' : String(value)}`)
        .join(' | ');
}

function MetricCard({ title, value, detail }: { title: string; value: string; detail?: string | null }) {
    return (
        <div className="rounded border border-slate-700 bg-slate-900/40 p-3">
            <p className="text-xs uppercase tracking-wider text-slate-400">{title}</p>
            <p className="mt-2 text-lg font-semibold text-white">{value}</p>
            {detail && <p className="mt-1 text-xs text-slate-400">{detail}</p>}
        </div>
    );
}

function OrderTable({
    title,
    orders,
    emptyLabel,
    variant,
}: {
    title: string;
    orders: LiveTradeExecutionDTO[];
    emptyLabel: string;
    variant: 'active' | 'completed';
}) {
    return (
        <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
            <div className="flex items-center justify-between gap-3">
                <h2 className="text-lg font-semibold text-white">{title}</h2>
                <span className="text-xs text-slate-400">{orders.length} shown</span>
            </div>

            {orders.length === 0 ? (
                <p className="mt-4 text-sm text-slate-400">{emptyLabel}</p>
            ) : (
                <div className="mt-4 overflow-x-auto">
                    <table className="min-w-full text-sm text-slate-200">
                        <thead className="text-left text-xs uppercase tracking-wider text-slate-400">
                            <tr className="border-b border-slate-700">
                                <th className="pb-2 pr-4">Symbol</th>
                                <th className="pb-2 pr-4">Side</th>
                                <th className="pb-2 pr-4">Open Time</th>
                                <th className="pb-2 pr-4">Status</th>
                                {variant === 'active' ? (
                                    <>
                                        <th className="pb-2 pr-4">Live PnL</th>
                                        <th className="pb-2 pr-4">Mark / Entry</th>
                                        <th className="pb-2 pr-4">Last Sync</th>
                                    </>
                                ) : (
                                    <>
                                        <th className="pb-2 pr-4">Close Time</th>
                                        <th className="pb-2 pr-4">Realized PnL</th>
                                        <th className="pb-2 pr-4">Closure Reason</th>
                                    </>
                                )}
                                <th className="pb-2">Execution Error</th>
                            </tr>
                        </thead>
                        <tbody>
                            {orders.map((order) => {
                                const completed = isCompletedExecutionState(order.executionState);
                                const pnl = completed
                                    ? order.realizedNetPnlUsdt
                                    : (order.unrealizedNetPnlUsdt ?? order.realizedNetPnlUsdt);
                                const pnlTone = pnl == null ? 'text-slate-200' : pnl >= 0 ? 'text-emerald-300' : 'text-rose-300';
                                const errorText = order.errorCode
                                    ? `${order.errorCode}${order.errorMessage ? `: ${order.errorMessage}` : ''}`
                                    : (order.errorMessage ?? 'n/a');
                                const closeTime = completed
                                    ? formatInstant(order.completedAt ?? order.updatedAt)
                                    : 'n/a';

                                return (
                                    <tr key={order.id} className="border-b border-slate-800 last:border-b-0">
                                        <td className="py-3 pr-4">
                                            <Link
                                                to={`/recommendation/${order.recommendationId}`}
                                                className="font-semibold text-sky-300 hover:text-sky-200"
                                            >
                                                {order.symbol}
                                            </Link>
                                        </td>
                                        <td className={`py-3 pr-4 font-semibold ${order.side === 'SELL' || order.side === 'SHORT' ? 'text-rose-300' : 'text-emerald-300'}`}>
                                            {order.side}
                                        </td>
                                        <td className="py-3 pr-4">{formatInstant(order.submittedAt ?? order.createdAt)}</td>
                                        <td className="py-3 pr-4">{order.executionState}</td>
                                        {variant === 'active' ? (
                                            <>
                                                <td className={`py-3 pr-4 font-semibold ${pnlTone}`}>{formatSignedMoney(pnl)}</td>
                                                <td className="py-3 pr-4">
                                                    <div>Mark {formatMarketValue(order.markPrice)}</div>
                                                    <div className="text-xs text-slate-400">Entry {formatMarketValue(order.entryPrice)}</div>
                                                </td>
                                                <td className="py-3 pr-4">
                                                    <div>{formatInstant(order.syncHealth?.lastSuccessfulSyncAt ?? order.lastReconciledAt ?? order.updatedAt)}</div>
                                                    <div className="text-xs text-slate-400">{order.syncHealth?.status ?? 'n/a'}</div>
                                                </td>
                                            </>
                                        ) : (
                                            <>
                                                <td className="py-3 pr-4">{closeTime}</td>
                                                <td className={`py-3 pr-4 font-semibold ${pnlTone}`}>{formatSignedMoney(pnl)}</td>
                                                <td className="py-3 pr-4">{order.closeReason ?? 'n/a'}</td>
                                            </>
                                        )}
                                        <td className="py-3">{errorText}</td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                </div>
            )}
        </section>
    );
}

function outcomeTone(outcome: string): string {
    if (outcome === 'WIN') {
        return 'text-emerald-300';
    }
    if (outcome === 'LOSS') {
        return 'text-rose-300';
    }
    return 'text-slate-200';
}

function matchesTradeState(item: BudgetTargetTradeHistoryItemDTO, filter: string): boolean {
    if (filter === 'ALL') {
        return true;
    }
    const isCompleted = ['CLOSED', 'FAILED', 'PREFLIGHT_REJECTED'].includes(item.executionState);
    return filter === 'ACTIVE' ? !isCompleted : isCompleted;
}

function isCompletedExecutionState(state: string | null | undefined): boolean {
    return state === 'CLOSED' || state === 'FAILED' || state === 'PREFLIGHT_REJECTED';
}

export function BudgetTargetAutoExecutionPage() {
    const { can } = usePermissions();
    const [searchParams, setSearchParams] = useSearchParams();
    const auditAllowed = can('live.execution.auto_session.audit.view');
    const selectedSessionId = searchParams.get('sessionId');
    const {
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
        auditTransportMode,
        refresh,
        startSession,
        stopSession,
        loadTradeDetail,
        clearTradeDetail,
        loadAuditReplay,
    } = useBudgetTargetAutoExecution({
        includeAudit: auditAllowed,
        selectedSessionId,
    });
    const pushToast = useToastStore((store) => store.pushToast);

    const [budgetInput, setBudgetInput] = useState('');
    const [targetInput, setTargetInput] = useState('');
    const [timelineCategory, setTimelineCategory] = useState('ALL');
    const [timelineSeverity, setTimelineSeverity] = useState('ALL');
    const [timelineExecutionId, setTimelineExecutionId] = useState('ALL');
    const [tradeState, setTradeState] = useState('ALL');
    const initializedDefaultsRef = useRef(false);

    useEffect(() => {
        if (auditAllowed && !selectedSessionId && resolvedSessionId) {
            const next = new URLSearchParams(searchParams);
            next.set('sessionId', resolvedSessionId);
            setSearchParams(next, { replace: true });
        }
    }, [auditAllowed, resolvedSessionId, searchParams, selectedSessionId, setSearchParams]);

    useEffect(() => {
        if (initializedDefaultsRef.current || !state || sessionIsActive) {
            return;
        }
        setBudgetInput(formatEditable(state.config.defaultBudgetUsdt));
        setTargetInput(formatEditable(state.config.defaultTargetProfitUsdt));
        initializedDefaultsRef.current = true;
    }, [sessionIsActive, state]);

    const manageAllowed = can('live.execution.auto_session.manage');
    const toggleChecked = sessionIsActive || Boolean(state?.config.armed);
    const displayedBudget = sessionIsActive
        ? formatEditable(session?.budgetAmountUsdt ?? state?.config.defaultBudgetUsdt)
        : budgetInput;
    const displayedTarget = sessionIsActive
        ? formatEditable(session?.targetProfitUsdt ?? state?.config.defaultTargetProfitUsdt)
        : targetInput;

    const parsedBudget = Number(budgetInput);
    const parsedTarget = Number(targetInput);
    const inputsValid = Number.isFinite(parsedBudget)
        && parsedBudget > 0
        && Number.isFinite(parsedTarget)
        && parsedTarget > 0;
    const startBlockedByConfig = !state?.config.enabled
        || Boolean(state?.config.killSwitch)
        || !state?.config.allowNewSessionStart;
    const startDisabled = loading
        || updating
        || !manageAllowed
        || !state
        || toggleChecked
        || !inputsValid
        || startBlockedByConfig;
    const stopDisabled = loading || updating || !manageAllowed || !state || !toggleChecked;

    const handleToggle = async () => {
        try {
            if (toggleChecked) {
                const requiresConfirm = Boolean(state?.config.requireOperatorConfirmationForStop);
                if (requiresConfirm && !window.confirm('Confirm graceful stop for the active budget-target auto session?')) {
                    return;
                }
                await stopSession({
                    confirmStop: requiresConfirm ? true : undefined,
                });
                pushToast('Auto session stop requested', 'success');
                return;
            }

            await startSession({
                budgetAmountUsdt: parsedBudget,
                targetProfitUsdt: parsedTarget,
            });
            pushToast('Auto session start requested', 'success');
        } catch {
            pushToast(toggleChecked ? 'Failed to stop auto session' : 'Failed to start auto session', 'error');
        }
    };

    const auditSummary = sessionDetail?.summary ?? null;
    const filteredTimeline = useMemo(() => {
        const items = timeline?.items ?? [];
        return items.filter((item) => {
            if (timelineCategory !== 'ALL' && item.eventCategory !== timelineCategory) {
                return false;
            }
            if (timelineSeverity !== 'ALL' && item.severity !== timelineSeverity) {
                return false;
            }
            if (timelineExecutionId !== 'ALL' && item.executionId !== timelineExecutionId) {
                return false;
            }
            return true;
        });
    }, [timeline, timelineCategory, timelineExecutionId, timelineSeverity]);

    const filteredTrades = useMemo(() => {
        const items = tradeHistory?.items ?? [];
        return items.filter((item) => matchesTradeState(item, tradeState));
    }, [tradeHistory, tradeState]);

    const executionOptions = useMemo(() => {
        const ids = new Map<string, string>();
        for (const item of timeline?.items ?? []) {
            if (item.executionId) {
                ids.set(item.executionId, item.symbol ? `${item.symbol} (${item.executionId.slice(0, 8)})` : item.executionId.slice(0, 8));
            }
        }
        return Array.from(ids.entries());
    }, [timeline]);

    if (loading && !state) {
        return (
            <div className="space-y-6">
                <section className="rounded-xl border border-slate-700 bg-slate-800 p-6 shadow-lg">
                    <h1 className="text-3xl font-bold text-white">Budget Target Auto-Execution</h1>
                    <p className="mt-2 text-sm text-slate-400">Loading backend state...</p>
                </section>
            </div>
        );
    }

    if (!state) {
        return (
            <div className="space-y-6">
                <Banner
                    message={error ? `${error.errorCode}: ${error.message}` : 'Unable to load auto-session state.'}
                    onRetry={() => void refresh(false)}
                />
            </div>
        );
    }

    return (
        <div className="space-y-6">
            <section className="rounded-xl border border-slate-700 bg-slate-800 p-6 shadow-lg">
                <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                        <h1 className="text-3xl font-bold text-white">Budget Target Auto-Execution</h1>
                        <p className="mt-2 text-sm text-slate-400">
                            Start or stop a budget-target session, monitor progress toward the realized net PnL target, and review active and completed trades.
                        </p>
                    </div>
                    <div className="space-y-2 text-right text-xs text-slate-400">
                        <p>Last updated: {formatInstant(lastUpdatedAt)}</p>
                        {refreshing && <p className="text-sky-300">Refreshing runtime...</p>}
                        {auditAllowed && (
                            <p className={`inline-flex rounded-full border px-3 py-1 font-semibold uppercase tracking-wider ${transportTone(auditTransportMode)}`}>
                                {auditTransportMode}
                            </p>
                        )}
                    </div>
                </div>

                {error && !reconnecting && (
                    <div className="mt-4 rounded border border-rose-700/50 bg-rose-900/20 p-3 text-sm text-rose-100">
                        {error.errorCode}: {error.message}
                    </div>
                )}

                <div className="mt-6 grid grid-cols-1 gap-4 lg:grid-cols-[1.3fr_0.7fr]">
                    <div className="rounded-xl border border-slate-700 bg-slate-900/40 p-4">
                        <div className="flex flex-wrap items-center justify-between gap-3">
                            <div>
                                <p className="text-xs uppercase tracking-wider text-slate-400">Session Control</p>
                                <p className="mt-1 text-sm text-slate-300">
                                    OFF starts a new session with the entered budget and target. ON requests a graceful stop.
                                </p>
                            </div>
                            <button
                                type="button"
                                role="switch"
                                aria-checked={toggleChecked}
                                aria-label="Budget Target Auto-Execution toggle"
                                disabled={toggleChecked ? stopDisabled : startDisabled}
                                onClick={() => void handleToggle()}
                                className={`relative inline-flex h-10 w-32 items-center rounded-full border px-1 transition ${
                                    toggleChecked
                                        ? 'border-emerald-600 bg-emerald-900/40'
                                        : 'border-slate-600 bg-slate-700'
                                } ${(toggleChecked ? stopDisabled : startDisabled) ? 'opacity-60' : ''}`}
                            >
                                <span className={`h-8 w-12 rounded-full transition ${toggleChecked ? 'translate-x-[72px] bg-emerald-400' : 'bg-slate-300'}`} />
                                <span className="absolute left-4 text-xs font-semibold text-slate-100">
                                    {updating ? '...' : toggleChecked ? 'ON' : 'OFF'}
                                </span>
                            </button>
                        </div>

                        <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2">
                            <label className="rounded border border-slate-700 bg-slate-950/50 p-3">
                                <span className="text-xs uppercase tracking-wider text-slate-400">Budget (USDT)</span>
                                <input
                                    type="number"
                                    min="0.01"
                                    step="0.01"
                                    value={displayedBudget}
                                    onChange={(event) => setBudgetInput(event.target.value)}
                                    disabled={sessionIsActive || updating}
                                    className="mt-2 w-full rounded border border-slate-600 bg-slate-950 px-3 py-2 text-sm text-white disabled:opacity-70"
                                />
                            </label>
                            <label className="rounded border border-slate-700 bg-slate-950/50 p-3">
                                <span className="text-xs uppercase tracking-wider text-slate-400">Final Target (USDT)</span>
                                <input
                                    type="number"
                                    min="0.01"
                                    step="0.01"
                                    value={displayedTarget}
                                    onChange={(event) => setTargetInput(event.target.value)}
                                    disabled={sessionIsActive || updating}
                                    className="mt-2 w-full rounded border border-slate-600 bg-slate-950 px-3 py-2 text-sm text-white disabled:opacity-70"
                                />
                            </label>
                        </div>

                        {!manageAllowed && (
                            <p className="mt-3 text-xs text-amber-300">
                                The `live.execution.auto_session.manage` permission is disabled.
                            </p>
                        )}
                        {!sessionIsActive && !inputsValid && (
                            <p className="mt-3 text-xs text-amber-300">Budget and final target must both be greater than 0.</p>
                        )}
                        {!sessionIsActive && startBlockedByConfig && (
                            <p className="mt-3 text-xs text-amber-300">Control Center is currently blocking new session starts.</p>
                        )}
                    </div>

                    <div className="rounded-xl border border-slate-700 bg-slate-900/40 p-4">
                        <p className="text-xs uppercase tracking-wider text-slate-400">Current Runtime Session</p>
                        <div className="mt-3 flex items-center justify-between gap-3">
                            <span className={`rounded-full border px-3 py-1 text-xs font-semibold uppercase tracking-wider ${statusTone(session?.status)}`}>
                                {session?.status ?? 'IDLE'}
                            </span>
                            {session?.pendingScanRunId && (
                                <span className="rounded-full border border-sky-700/50 bg-sky-900/20 px-3 py-1 text-xs font-semibold uppercase tracking-wider text-sky-200">
                                    Pending scan
                                </span>
                            )}
                        </div>
                        <div className="mt-4 space-y-2 text-sm text-slate-300">
                            <p>Started: {formatInstant(session?.startedAt)}</p>
                            <p>Completed: {formatInstant(session?.completedAt)}</p>
                            <p>Target: {formatMoney(session?.targetProfitUsdt ?? state.config.defaultTargetProfitUsdt)}</p>
                            <p>Budget: {formatMoney(session?.budgetAmountUsdt ?? state.config.defaultBudgetUsdt)}</p>
                        </div>
                    </div>
                </div>
            </section>

            <section className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-5">
                <MetricCard title="Session Status" value={session?.status ?? 'IDLE'} detail={session?.stopReasonMessage ?? undefined} />
                <MetricCard
                    title="Active Trades"
                    value={`${session?.activeTradeCount ?? 0} / ${session?.activeTradeLimit ?? state.config.maxConcurrentPositions}`}
                    detail={`${activeOrders.length} visible in runtime list`}
                />
                <MetricCard
                    title="Realized Net PnL"
                    value={formatMoney(session?.realizedNetPnlUsdt)}
                    detail={session?.completionReason ?? undefined}
                />
                <MetricCard
                    title="Target Progress"
                    value={`${targetProgressPct.toFixed(0)}%`}
                    detail={`Target ${formatMoney(session?.targetProfitUsdt ?? state.config.defaultTargetProfitUsdt)}`}
                />
                <MetricCard
                    title="Remaining to Target"
                    value={formatMoney(remainingToTargetUsdt)}
                    detail={session?.pendingScanRunId ? `Scan ${session.pendingScanRunId}` : undefined}
                />
            </section>

            <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
                <div className="flex items-center justify-between gap-3">
                    <h2 className="text-lg font-semibold text-white">Target Progress</h2>
                    <span className="text-xs text-slate-400">
                        Realized {formatMoney(session?.realizedNetPnlUsdt)} of {formatMoney(session?.targetProfitUsdt ?? state.config.defaultTargetProfitUsdt)}
                    </span>
                </div>
                <div className="mt-4 h-3 w-full rounded bg-slate-700">
                    <div
                        className="h-3 rounded bg-emerald-500 transition-[width]"
                        style={{ width: `${targetProgressPct}%` }}
                    />
                </div>
            </section>

            <OrderTable
                title="Active / Open Trades"
                orders={activeOrders}
                emptyLabel="No active session-owned trades."
                variant="active"
            />

            <OrderTable
                title="Completed Trades / Results"
                orders={completedOrders}
                emptyLabel="No completed session-owned trades."
                variant="completed"
            />

            <section className={`rounded-xl border p-5 shadow-lg ${blockerTone(primaryBlockedReason)}`}>
                <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                        <h2 className="text-lg font-semibold text-white">Health and Blockers</h2>
                        <p className="mt-1 text-sm">{primaryBlockedReason.message}</p>
                    </div>
                    <span className="rounded-full border border-current/30 px-3 py-1 text-xs font-semibold uppercase tracking-wider">
                        {primaryBlockedReason.source}
                    </span>
                </div>

                <div className="mt-4 grid grid-cols-1 gap-3 text-sm md:grid-cols-4">
                    <div className="rounded border border-current/20 bg-black/10 p-3">
                        <p className="text-xs uppercase tracking-wider opacity-70">Session Sync</p>
                        <p className={`mt-2 inline-flex rounded-full border px-3 py-1 text-xs font-semibold uppercase tracking-wider ${syncTone(syncHealth?.status)}`}>
                            {syncHealth?.status ?? 'IDLE'}
                        </p>
                    </div>
                    <div className="rounded border border-current/20 bg-black/10 p-3">
                        <p className="text-xs uppercase tracking-wider opacity-70">Last Successful Sync</p>
                        <p className="mt-2">{formatInstant(syncHealth?.lastSuccessfulSyncAt)}</p>
                    </div>
                    <div className="rounded border border-current/20 bg-black/10 p-3">
                        <p className="text-xs uppercase tracking-wider opacity-70">Synced Exposure</p>
                        <p className="mt-2">
                            {syncHealth?.openPositionCount ?? session?.activeTradeCount ?? 0} positions / {syncHealth?.activeOpenOrderCount ?? 0} orders
                        </p>
                    </div>
                    <div className="rounded border border-current/20 bg-black/10 p-3">
                        <p className="text-xs uppercase tracking-wider opacity-70">Close-All / Gate</p>
                        <p className="mt-2">
                            {syncHealth?.closeAllInProgress ? 'CLOSE-ALL IN FLIGHT' : syncHealth?.gateNewTrades ? 'NEW TRADES BLOCKED' : 'CLEAR'}
                        </p>
                        <p className="mt-1 text-xs opacity-70">
                            {syncHealth?.gateReasonCode ?? syncHealth?.latestErrorCode ?? health?.summary.primaryBlockerCode ?? 'n/a'}
                        </p>
                    </div>
                </div>

                {!sessionIsActive && (
                    <div className="mt-4 rounded border border-current/20 bg-black/10 p-3 text-sm">
                        <p className="text-xs uppercase tracking-wider opacity-70">Generic Runtime Health</p>
                        <p className="mt-2">
                            Connection {health?.summary.connectionStatus ?? 'UNKNOWN'} | Runtime {health?.runtime.runtimeReady ? 'READY' : 'BLOCKED'} | Health check {health?.executable ? 'PASS' : 'BLOCKED'}
                        </p>
                    </div>
                )}
            </section>

            {!auditAllowed && (
                <>
                    <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
                        <h2 className="text-lg font-semibold text-white">Audit Access Required</h2>
                        <p className="mt-2 text-sm text-slate-400">
                            The `live.execution.auto_session.audit.view` permission is required for timeline, trade history, and replay data.
                        </p>
                    </section>
                </>
            )}

            {auditAllowed && (
                <details className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
                    <summary className="cursor-pointer list-none text-lg font-semibold text-white">
                        Audit, Replay, and Trade Drill-Down
                    </summary>
                    <p className="mt-2 text-sm text-slate-400">
                        Open this section for the persisted audit report, event timeline, detailed trade history, and replay payloads.
                    </p>
                    <div className="mt-5 space-y-6">
                    <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
                        <div className="flex flex-wrap items-center justify-between gap-3">
                            <div>
                                <h2 className="text-lg font-semibold text-white">Session Audit Report</h2>
                                <p className="mt-1 text-sm text-slate-400">
                                    Operator-readable report built from persisted session, trade, and exchange events.
                                </p>
                            </div>
                            <div className="flex flex-wrap items-center gap-2">
                                <label className="text-xs text-slate-400" htmlFor="audit-session-selector">Session</label>
                                <select
                                    id="audit-session-selector"
                                    aria-label="Audit session selector"
                                    value={resolvedSessionId ?? ''}
                                    onChange={(event) => {
                                        const next = new URLSearchParams(searchParams);
                                        next.set('sessionId', event.target.value);
                                        setSearchParams(next);
                                    }}
                                    className="rounded border border-slate-600 bg-slate-950 px-3 py-2 text-sm text-white"
                                >
                                    {(sessions.length === 0 ? [{ id: '', startedAt: null, status: 'UNKNOWN' }] : sessions).map((item) => (
                                        <option key={item.id || 'none'} value={item.id}>
                                            {item.id
                                                ? `${formatInstant(item.startedAt)} | ${item.status} | ${item.id.slice(0, 8)}`
                                                : 'No sessions'}
                                        </option>
                                    ))}
                                </select>
                                <button
                                    type="button"
                                    onClick={() => void loadAuditReplay()}
                                    disabled={!resolvedSessionId || auditReplayLoading}
                                    className="rounded border border-sky-700/50 bg-sky-900/20 px-3 py-2 text-xs font-semibold text-sky-200 disabled:opacity-60"
                                >
                                    {auditReplayLoading ? 'Loading replay...' : 'Load replay payload'}
                                </button>
                            </div>
                        </div>

                        {(auditLoading || auditRefreshing) && (
                            <p className="mt-4 text-sm text-sky-300">
                                {auditLoading ? 'Loading session audit...' : 'Refreshing session audit...'}
                            </p>
                        )}

                        {auditSummary && (
                            <>
                                <div className="mt-5 grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
                                    <MetricCard title="Selected Session" value={auditSummary.status} detail={auditSummary.id} />
                                    <MetricCard title="Started By" value={auditSummary.startedBy ?? 'system'} detail={auditSummary.startReason ?? 'n/a'} />
                                    <MetricCard title="Final Target Satisfied" value={formatInstant(auditSummary.targetSatisfiedAt)} detail={auditSummary.stopReason ?? 'n/a'} />
                                    <MetricCard title="Critical Error" value={auditSummary.mostRecentCriticalError?.code ?? 'none'} detail={auditSummary.mostRecentCriticalError?.message ?? undefined} />
                                </div>

                                <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-6">
                                    <MetricCard title="Net PnL" value={formatSignedMoney(auditSummary.realizedNetPnlUsdt)} />
                                    <MetricCard title="Gross PnL" value={formatSignedMoney(auditSummary.totalGrossPnlUsdt)} />
                                    <MetricCard title="Fees" value={formatMoney(auditSummary.feeTotalUsdt)} />
                                    <MetricCard title="Wins / Losses" value={`${auditSummary.winCount} / ${auditSummary.lossCount}`} />
                                    <MetricCard title="Active Trades" value={`${auditSummary.activeTradeCount}`} />
                                    <MetricCard title="Completed Trades" value={`${auditSummary.completedTradeCount}`} />
                                </div>

                                <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
                                    <div className="rounded border border-slate-700 bg-slate-900/40 p-4 text-sm text-slate-300">
                                        <p className="text-xs uppercase tracking-wider text-slate-400">Config Snapshot</p>
                                        <div className="mt-3 space-y-2">
                                            <p>Budget: {formatMoney(sessionDetail?.configSnapshot.sessionBudgetUsdt)}</p>
                                            <p>Target: {formatMoney(sessionDetail?.configSnapshot.targetProfitUsdt)}</p>
                                            <p>Max positions: {sessionDetail?.configSnapshot.maxConcurrentPositions ?? 'n/a'}</p>
                                            <p>Trace ID: {sessionDetail?.traceId ?? 'n/a'}</p>
                                            <p>Pending scan: {sessionDetail?.pendingScanRunId ?? 'n/a'}</p>
                                            <p>Last event: {formatInstant(sessionDetail?.lastEventAt)}</p>
                                            <p>Timeline events: {sessionDetail?.timelineEventCount ?? 0}</p>
                                            <p>Trades: {sessionDetail?.tradeCount ?? 0}</p>
                                        </div>
                                    </div>
                                    <div className="rounded border border-slate-700 bg-slate-900/40 p-4 text-sm text-slate-300">
                                        <p className="text-xs uppercase tracking-wider text-slate-400">Runtime Flags</p>
                                        <div className="mt-3 space-y-2">
                                            <p>Armed: {sessionDetail?.configSnapshot.autoTargetMode.armed ? 'YES' : 'NO'}</p>
                                            <p>New starts allowed: {sessionDetail?.configSnapshot.autoTargetMode.allowNewSessionStart ? 'YES' : 'NO'}</p>
                                            <p>Close-all on target: {sessionDetail?.configSnapshot.autoTargetMode.allowCloseAllOnTarget ? 'YES' : 'NO'}</p>
                                            <p>Kill switch: {sessionDetail?.configSnapshot.autoTargetMode.killSwitch ? 'ON' : 'OFF'}</p>
                                            <p>Health pass required: {sessionDetail?.configSnapshot.autoTargetMode.requireBinanceHealthPass ? 'YES' : 'NO'}</p>
                                            <p>Stop confirmation required: {sessionDetail?.configSnapshot.autoTargetMode.requireOperatorConfirmationForStop ? 'YES' : 'NO'}</p>
                                            <p>Session timeout: {sessionDetail?.configSnapshot.autoTargetMode.sessionTimeoutMinutes ?? 'n/a'} min</p>
                                            <p>Control Center version: {sessionDetail?.configSnapshot.controlCenterVersion ?? 'n/a'}</p>
                                        </div>
                                    </div>
                                </div>
                            </>
                        )}

                        {auditReplay && (
                            <div className="mt-4 rounded border border-slate-700 bg-slate-950/40 p-4 text-sm text-slate-300">
                                <div className="flex flex-wrap items-center justify-between gap-3">
                                    <div>
                                        <p className="text-xs uppercase tracking-wider text-slate-400">Replay Payload</p>
                                        <p className="mt-1">
                                            Generated {formatInstant(auditReplay.generatedAt)} with {auditReplay.timeline.length} events and {Object.keys(auditReplay.trades).length} trades.
                                        </p>
                                    </div>
                                </div>
                                <details className="mt-3">
                                    <summary className="cursor-pointer text-xs font-semibold uppercase tracking-wider text-sky-300">Show replay metadata</summary>
                                    <pre className="mt-3 overflow-x-auto rounded bg-slate-950 p-3 text-xs text-slate-200">
                                        {jsonText({
                                            generatedAt: auditReplay.generatedAt,
                                            sessionId: auditReplay.session?.summary?.id ?? null,
                                            timelineCount: auditReplay.timeline.length,
                                            tradeIds: Object.keys(auditReplay.trades),
                                        })}
                                    </pre>
                                </details>
                            </div>
                        )}
                    </section>

                    <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
                        <div className="flex flex-wrap items-end justify-between gap-3">
                            <div>
                                <h2 className="text-lg font-semibold text-white">Event Timeline</h2>
                                <p className="mt-1 text-sm text-slate-400">
                                    Why the session started, why trades opened or were rejected, exchange responses, and stop conditions.
                                </p>
                            </div>
                            <div className="flex flex-wrap items-center gap-2 text-sm">
                                <label className="text-slate-400" htmlFor="timeline-category">Category</label>
                                <select
                                    id="timeline-category"
                                    value={timelineCategory}
                                    onChange={(event) => setTimelineCategory(event.target.value)}
                                    className="rounded border border-slate-600 bg-slate-950 px-2 py-1 text-white"
                                >
                                    <option value="ALL">All</option>
                                    <option value="SESSION">Session</option>
                                    <option value="TRADE">Trade</option>
                                    <option value="EXCHANGE">Exchange</option>
                                </select>
                                <label className="text-slate-400" htmlFor="timeline-severity">Severity</label>
                                <select
                                    id="timeline-severity"
                                    value={timelineSeverity}
                                    onChange={(event) => setTimelineSeverity(event.target.value)}
                                    className="rounded border border-slate-600 bg-slate-950 px-2 py-1 text-white"
                                >
                                    <option value="ALL">All</option>
                                    <option value="INFO">Info</option>
                                    <option value="WARN">Warn</option>
                                    <option value="ERROR">Error</option>
                                </select>
                                <label className="text-slate-400" htmlFor="timeline-execution">Trade</label>
                                <select
                                    id="timeline-execution"
                                    value={timelineExecutionId}
                                    onChange={(event) => setTimelineExecutionId(event.target.value)}
                                    className="rounded border border-slate-600 bg-slate-950 px-2 py-1 text-white"
                                >
                                    <option value="ALL">All</option>
                                    {executionOptions.map(([id, label]) => (
                                        <option key={id} value={id}>{label}</option>
                                    ))}
                                </select>
                            </div>
                        </div>

                        <div className="mt-4 overflow-x-auto">
                            <table className="min-w-full text-sm text-slate-200">
                                <thead className="text-left text-xs uppercase tracking-wider text-slate-400">
                                    <tr className="border-b border-slate-700">
                                        <th className="pb-2 pr-4">Time</th>
                                        <th className="pb-2 pr-4">Category</th>
                                        <th className="pb-2 pr-4">Severity</th>
                                        <th className="pb-2 pr-4">Type</th>
                                        <th className="pb-2 pr-4">Symbol</th>
                                        <th className="pb-2 pr-4">Message</th>
                                        <th className="pb-2 pr-4">Reason</th>
                                        <th className="pb-2 pr-4">Actor</th>
                                        <th className="pb-2">Summary</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {filteredTimeline.length === 0 && (
                                        <tr>
                                            <td className="py-4 text-slate-400" colSpan={9}>No timeline events match the selected filters.</td>
                                        </tr>
                                    )}
                                    {filteredTimeline.map((item) => (
                                        <tr key={item.id} className="border-b border-slate-800 last:border-b-0 align-top">
                                            <td className="py-3 pr-4 whitespace-nowrap">{formatInstant(item.eventTs)}</td>
                                            <td className="py-3 pr-4">{item.eventCategory}</td>
                                            <td className={`py-3 pr-4 font-semibold ${severityTone(item.severity)}`}>{item.severity}</td>
                                            <td className="py-3 pr-4">{item.eventType}</td>
                                            <td className="py-3 pr-4">{item.symbol ?? 'n/a'}</td>
                                            <td className="py-3 pr-4">{item.message ?? 'n/a'}</td>
                                            <td className="py-3 pr-4">{item.reasonCode ?? item.status ?? 'n/a'}</td>
                                            <td className="py-3 pr-4">{item.actor ?? 'system'}</td>
                                            <td className="py-3 text-xs text-slate-300">{formatTimelineSummary(item)}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </section>

                    <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
                        <div className="flex flex-wrap items-end justify-between gap-3">
                            <div>
                                <h2 className="text-lg font-semibold text-white">Trade History</h2>
                                <p className="mt-1 text-sm text-slate-400">
                                    Budget slices, open reasons, close reasons, winners, losers, and latest critical failures.
                                </p>
                            </div>
                            <div className="flex items-center gap-2 text-sm">
                                <label className="text-slate-400" htmlFor="trade-state-filter">State</label>
                                <select
                                    id="trade-state-filter"
                                    value={tradeState}
                                    onChange={(event) => setTradeState(event.target.value)}
                                    className="rounded border border-slate-600 bg-slate-950 px-2 py-1 text-white"
                                >
                                    <option value="ALL">All</option>
                                    <option value="ACTIVE">Active</option>
                                    <option value="COMPLETED">Completed</option>
                                </select>
                            </div>
                        </div>

                        <div className="mt-4 overflow-x-auto">
                            <table className="min-w-full text-sm text-slate-200">
                                <thead className="text-left text-xs uppercase tracking-wider text-slate-400">
                                    <tr className="border-b border-slate-700">
                                        <th className="pb-2 pr-4">Symbol</th>
                                        <th className="pb-2 pr-4">Side</th>
                                        <th className="pb-2 pr-4">State</th>
                                        <th className="pb-2 pr-4">Budget Slice</th>
                                        <th className="pb-2 pr-4">Reserved</th>
                                        <th className="pb-2 pr-4">Opened</th>
                                        <th className="pb-2 pr-4">Closed</th>
                                        <th className="pb-2 pr-4">Open Reason</th>
                                        <th className="pb-2 pr-4">Close Reason</th>
                                        <th className="pb-2 pr-4">Net PnL</th>
                                        <th className="pb-2 pr-4">Outcome</th>
                                        <th className="pb-2">Inspect</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {filteredTrades.length === 0 && (
                                        <tr>
                                            <td className="py-4 text-slate-400" colSpan={12}>No trades match the selected filter.</td>
                                        </tr>
                                    )}
                                    {filteredTrades.map((item) => (
                                        <tr key={item.executionId} className="border-b border-slate-800 last:border-b-0 align-top">
                                            <td className="py-3 pr-4">
                                                {item.recommendationId ? (
                                                    <Link to={`/recommendation/${item.recommendationId}`} className="font-semibold text-sky-300 hover:text-sky-200">
                                                        {item.symbol}
                                                    </Link>
                                                ) : item.symbol}
                                            </td>
                                            <td className={`py-3 pr-4 font-semibold ${item.side === 'SELL' || item.side === 'SHORT' ? 'text-rose-300' : 'text-emerald-300'}`}>
                                                {item.side}
                                            </td>
                                            <td className="py-3 pr-4">{item.executionState}</td>
                                            <td className="py-3 pr-4">{formatMoney(item.allocatedBudgetSliceUsdt)}</td>
                                            <td className="py-3 pr-4">{formatMoney(item.reservedMarginUsdt)}</td>
                                            <td className="py-3 pr-4 whitespace-nowrap">{formatInstant(item.openedAt)}</td>
                                            <td className="py-3 pr-4 whitespace-nowrap">{formatInstant(item.closedAt)}</td>
                                            <td className="py-3 pr-4">{item.openReason ?? 'n/a'}</td>
                                            <td className="py-3 pr-4">{item.closeReason ?? 'n/a'}</td>
                                            <td className={`py-3 pr-4 font-semibold ${outcomeTone(item.outcome)}`}>{formatSignedMoney(item.realizedNetPnlUsdt)}</td>
                                            <td className={`py-3 pr-4 font-semibold ${outcomeTone(item.outcome)}`}>{item.outcome}</td>
                                            <td className="py-3">
                                                <button
                                                    type="button"
                                                    onClick={() => void loadTradeDetail(item.executionId)}
                                                    className="rounded border border-sky-700/50 bg-sky-900/20 px-2 py-1 text-xs font-semibold text-sky-200"
                                                >
                                                    Inspect
                                                </button>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </section>

                    <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
                        <div className="flex flex-wrap items-center justify-between gap-3">
                            <div>
                                <h2 className="text-lg font-semibold text-white">Trade Drill-Down</h2>
                                <p className="mt-1 text-sm text-slate-400">
                                    Raw exchange responses, order records, closures, ledger entries, and decision audits for the selected trade.
                                </p>
                            </div>
                            {tradeDetail?.trade && (
                                <button
                                    type="button"
                                    onClick={() => clearTradeDetail()}
                                    className="rounded border border-slate-600 px-3 py-2 text-xs font-semibold text-slate-200"
                                >
                                    Close detail
                                </button>
                            )}
                        </div>

                        {tradeDetailLoading && <p className="mt-4 text-sm text-sky-300">Loading trade detail...</p>}
                        {!tradeDetailLoading && !tradeDetail?.trade && (
                            <p className="mt-4 text-sm text-slate-400">Select a trade to inspect raw order, closure, and exchange payloads.</p>
                        )}

                        {tradeDetail?.trade && (
                            <div className="mt-4 space-y-4">
                                <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-5">
                                    <MetricCard title="Trade" value={tradeDetail.trade.symbol} detail={tradeDetail.trade.executionId} />
                                    <MetricCard title="State" value={tradeDetail.trade.executionState} detail={tradeDetail.trade.triggerMode ?? undefined} />
                                    <MetricCard title="Budget Slice" value={formatMoney(tradeDetail.trade.allocatedBudgetSliceUsdt)} detail={tradeDetail.trade.openReason ?? undefined} />
                                    <MetricCard title="Close Reason" value={tradeDetail.trade.closeReason ?? 'n/a'} detail={tradeDetail.trade.latestCriticalError?.message ?? undefined} />
                                    <MetricCard title="Net Result" value={formatSignedMoney(tradeDetail.trade.realizedNetPnlUsdt)} detail={tradeDetail.trade.outcome} />
                                </div>

                                <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
                                    <MetricCard title="Sync Health" value={tradeDetail.execution?.syncHealth?.status ?? 'n/a'} detail={tradeDetail.execution?.syncHealth?.gateReasonMessage ?? tradeDetail.execution?.syncHealth?.latestErrorMessage ?? undefined} />
                                    <MetricCard title="Last Sync" value={formatInstant(tradeDetail.execution?.syncHealth?.lastSyncAt ?? tradeDetail.execution?.lastReconciledAt)} />
                                    <MetricCard title="Last Successful Sync" value={formatInstant(tradeDetail.execution?.syncHealth?.lastSuccessfulSyncAt)} />
                                    <MetricCard title="Synced Orders" value={`${tradeDetail.execution?.syncHealth?.activeOpenOrderCount ?? 0}`} detail={`${tradeDetail.execution?.syncHealth?.openPositionCount ?? 0} open positions`} />
                                </div>

                                <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                                    <div className="rounded border border-slate-700 bg-slate-900/40 p-4">
                                        <p className="text-xs uppercase tracking-wider text-slate-400">Decision Audits</p>
                                        <div className="mt-3 space-y-3 text-sm text-slate-200">
                                            {tradeDetail.decisionAudits.length === 0 && <p className="text-slate-400">No decision audits recorded.</p>}
                                            {tradeDetail.decisionAudits.map((item) => (
                                                <div key={item.id} className="rounded border border-slate-700 bg-slate-950/40 p-3">
                                                    <p className={`font-semibold ${severityTone(item.severity)}`}>{item.eventType}</p>
                                                    <p className="mt-1 text-xs text-slate-400">{formatInstant(item.eventTs)} | {item.reasonCode ?? item.status ?? 'n/a'}</p>
                                                    <p className="mt-2">{item.message ?? 'n/a'}</p>
                                                </div>
                                            ))}
                                        </div>
                                    </div>

                                    <div className="rounded border border-slate-700 bg-slate-900/40 p-4">
                                        <p className="text-xs uppercase tracking-wider text-slate-400">PnL Ledger</p>
                                        <div className="mt-3 space-y-3 text-sm text-slate-200">
                                            {tradeDetail.pnlLedgerEntries.length === 0 && <p className="text-slate-400">No ledger entries recorded.</p>}
                                            {tradeDetail.pnlLedgerEntries.map((entry) => (
                                                <div key={entry.id} className="rounded border border-slate-700 bg-slate-950/40 p-3">
                                                    <p className="font-semibold">{entry.eventType}</p>
                                                    <p className="mt-1 text-xs text-slate-400">{formatInstant(entry.eventTs)} | {entry.sourceType ?? 'n/a'}</p>
                                                    <p className="mt-2">{formatSignedMoney(entry.amountUsdt)}</p>
                                                </div>
                                            ))}
                                        </div>
                                    </div>
                                </div>

                                <div className="grid grid-cols-1 gap-4 xl:grid-cols-4">
                                    <div className="rounded border border-slate-700 bg-slate-900/40 p-4">
                                        <p className="text-xs uppercase tracking-wider text-slate-400">Exchange Response</p>
                                        <pre className="mt-3 overflow-x-auto rounded bg-slate-950 p-3 text-xs text-slate-200">
                                            {jsonText(tradeDetail.execution?.exchangeResponse)}
                                        </pre>
                                    </div>
                                    <div className="rounded border border-slate-700 bg-slate-900/40 p-4">
                                        <p className="text-xs uppercase tracking-wider text-slate-400">Orders</p>
                                        <pre className="mt-3 overflow-x-auto rounded bg-slate-950 p-3 text-xs text-slate-200">
                                            {jsonText(tradeDetail.orders)}
                                        </pre>
                                    </div>
                                    <div className="rounded border border-slate-700 bg-slate-900/40 p-4">
                                        <p className="text-xs uppercase tracking-wider text-slate-400">Closure</p>
                                        <pre className="mt-3 overflow-x-auto rounded bg-slate-950 p-3 text-xs text-slate-200">
                                            {jsonText(tradeDetail.closure)}
                                        </pre>
                                    </div>
                                    <div className="rounded border border-slate-700 bg-slate-900/40 p-4">
                                        <p className="text-xs uppercase tracking-wider text-slate-400">Sync Snapshots</p>
                                        <pre className="mt-3 overflow-x-auto rounded bg-slate-950 p-3 text-xs text-slate-200">
                                            {jsonText(tradeDetail.syncSnapshots)}
                                        </pre>
                                    </div>
                                </div>
                            </div>
                        )}
                    </section>
                    </div>
                </details>
            )}

            <div className="text-xs text-slate-400">
                Audit reads are backed by persisted session events, decision audits, trade execution events, orders, closures, and PnL ledger rows.
            </div>
        </div>
    );
}
