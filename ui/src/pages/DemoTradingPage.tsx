import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
    Bar,
    BarChart,
    CartesianGrid,
    Line,
    LineChart,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis,
} from 'recharts';
import { useVirtualizer } from '@tanstack/react-virtual';
import {
    demoApi,
    type DemoAiSuggestionLatest,
    type DemoAnalyticsSummary,
    type DemoStatusResponse,
    type DemoTradeRow,
} from '../api/demoApi';
import { Banner } from '../components/Banner';
import { DemoAiSuggestionsCard } from '../components/demo/DemoAiSuggestionsCard';
import { DemoAcceptModal } from '../components/demo/DemoAcceptModal';
import { DemoTradeDetailDrawer } from '../components/demo/DemoTradeDetailDrawer';
import { parseApiError } from '../utils/apiError';
import { getErrorExplanation } from '../utils/errorMap';
import { useControlCenter, usePermissions } from '../hooks/usePermissions';
import { useToastStore } from '../store/toastStore';
import { formatDateTime, formatSignedMoney, formatSignedR, formatTimeInTrade, toNumber } from '../components/demo/demoFormat';

interface UiError {
    errorCode: string;
    message: string;
    traceId: string | null;
    fix: string;
}

interface EquityPoint {
    at: string;
    equity: number;
}

const LOOKBACK: 10 | 50 | 100 = 50;
const PHASES = [
    'Cycle started',
    'Universe built',
    'Strategy evaluated',
    'Placeability checked',
    'Trade opened',
    'Monitoring',
    'Closed',
];

function normalizedWinRate(value: unknown): number {
    const num = toNumber(value as number | string | null | undefined);
    if (num === null) {
        return 0;
    }
    return num > 1 ? num / 100 : num;
}

function formatPct(value: unknown): string {
    const ratio = normalizedWinRate(value);
    return `${(ratio * 100).toFixed(2)}%`;
}

function KpiCard({ title, value, pulse }: { title: string; value: string; pulse?: boolean }) {
    return (
        <motion.div
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0, scale: pulse ? [1, 1.02, 1] : 1 }}
            transition={{ duration: 0.25 }}
            className="rounded-xl border border-slate-700 bg-slate-900/50 p-4"
        >
            <p className="text-xs uppercase tracking-wider text-slate-400">{title}</p>
            <p className="mt-2 text-xl font-bold text-slate-100">{value}</p>
        </motion.div>
    );
}

export function DemoTradingPage() {
    const navigate = useNavigate();
    const { id: routeTradeId } = useParams<{ id?: string }>();

    const { can } = usePermissions();
    const { state: controlCenterState, patchConfig } = useControlCenter();
    const pushToast = useToastStore((store) => store.pushToast);

    const [status, setStatus] = useState<DemoStatusResponse | null>(null);
    const [analytics, setAnalytics] = useState<DemoAnalyticsSummary | null>(null);
    const [trades, setTrades] = useState<DemoTradeRow[]>([]);
    const [suggestions, setSuggestions] = useState<DemoAiSuggestionLatest | null>(null);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [toggleBusy, setToggleBusy] = useState(false);
    const [actionBusy, setActionBusy] = useState<'accept' | 'reject' | null>(null);
    const [uiError, setUiError] = useState<UiError | null>(null);
    const [showAcceptModal, setShowAcceptModal] = useState(false);

    const [pulseOpen, setPulseOpen] = useState(false);
    const [pulseClosed, setPulseClosed] = useState(false);
    const prevCountsRef = useRef<{ open: number; closed: number }>({ open: 0, closed: 0 });

    const isDemoEnabled = Boolean(status?.enabled ?? controlCenterState?.config.demoTrading.enabled);
    const canToggleDemo = can('demo.enable_disable');
    const canAiAction = can('demo.ai.accept_reject');

    const fetchAll = useCallback(async (silent = false) => {
        if (!silent) {
            setLoading(true);
        } else {
            setRefreshing(true);
        }

        try {
            const [statusRes, analyticsRes, tradesRes, suggestionsRes] = await Promise.all([
                demoApi.getStatus(),
                demoApi.getAnalytics(LOOKBACK),
                demoApi.getTrades(200, 0),
                demoApi.getLatestSuggestions(),
            ]);

            setStatus(statusRes);
            setAnalytics(analyticsRes);
            setTrades(tradesRes.trades ?? []);
            setSuggestions(suggestionsRes);
            setUiError(null);
        } catch (error) {
            const parsed = parseApiError(error);
            const explain = getErrorExplanation(parsed.errorCode);
            setUiError({
                errorCode: parsed.errorCode,
                message: parsed.message,
                traceId: parsed.traceId,
                fix: explain.fix,
            });
        } finally {
            setLoading(false);
            setRefreshing(false);
        }
    }, []);

    useEffect(() => {
        void fetchAll();
    }, [fetchAll]);

    useEffect(() => {
        if (!isDemoEnabled) {
            return;
        }

        const intervalId = window.setInterval(() => {
            void fetchAll(true);
        }, 5000);

        return () => window.clearInterval(intervalId);
    }, [fetchAll, isDemoEnabled]);

    useEffect(() => {
        if (!status) {
            return;
        }

        const previous = prevCountsRef.current;
        if (status.openPositionsCount > previous.open) {
            setPulseOpen(true);
            window.setTimeout(() => setPulseOpen(false), 700);
        }
        if (status.closedTradesCount > previous.closed) {
            setPulseClosed(true);
            window.setTimeout(() => setPulseClosed(false), 700);
        }

        prevCountsRef.current = {
            open: status.openPositionsCount,
            closed: status.closedTradesCount,
        };
    }, [status]);

    const toggleDemo = async () => {
        if (!canToggleDemo || toggleBusy) {
            return;
        }

        setToggleBusy(true);
        setUiError(null);
        const target = !isDemoEnabled;

        try {
            await patchConfig(
                { demoTrading: { enabled: target } },
                target ? 'demo-toggle-on' : 'demo-toggle-off',
            );
            pushToast(target ? 'Demo trading enabled' : 'Demo trading paused', 'success');
            await fetchAll();
        } catch (error) {
            const parsed = parseApiError(error);
            const explain = getErrorExplanation(parsed.errorCode);
            setUiError({
                errorCode: parsed.errorCode,
                message: parsed.message,
                traceId: parsed.traceId,
                fix: explain.fix,
            });
            pushToast('Failed to update demo toggle', 'error');
        } finally {
            setToggleBusy(false);
        }
    };

    const handleAcceptBatch = async () => {
        const batchId = suggestions?.batch?.id;
        if (!batchId || !canAiAction) {
            return;
        }

        setActionBusy('accept');
        try {
            await demoApi.acceptBatch(batchId);
            setShowAcceptModal(false);
            pushToast('Demo AI batch accepted', 'success');
            await fetchAll(true);
        } catch (error) {
            const parsed = parseApiError(error);
            const explain = getErrorExplanation(parsed.errorCode);
            setUiError({
                errorCode: parsed.errorCode,
                message: parsed.message,
                traceId: parsed.traceId,
                fix: explain.fix,
            });
            pushToast('Accept failed', 'error');
        } finally {
            setActionBusy(null);
        }
    };

    const handleRejectBatch = async () => {
        const batchId = suggestions?.batch?.id;
        if (!batchId || !canAiAction) {
            return;
        }

        setActionBusy('reject');
        try {
            await demoApi.rejectBatch(batchId);
            pushToast('Demo AI batch rejected', 'success');
            await fetchAll(true);
        } catch (error) {
            const parsed = parseApiError(error);
            const explain = getErrorExplanation(parsed.errorCode);
            setUiError({
                errorCode: parsed.errorCode,
                message: parsed.message,
                traceId: parsed.traceId,
                fix: explain.fix,
            });
            pushToast('Reject failed', 'error');
        } finally {
            setActionBusy(null);
        }
    };

    const startBalance = controlCenterState?.config.demoTrading.startBalanceUsdt ?? 1000;

    const closedTrades = useMemo(() => trades.filter((trade) => trade.status === 'CLOSED'), [trades]);

    const equityData = useMemo<EquityPoint[]>(() => {
        let running = startBalance;
        const points: EquityPoint[] = [{ at: 'Start', equity: startBalance }];
        const sorted = [...closedTrades].sort((a, b) => {
            const aTs = a.closedAt ? Date.parse(a.closedAt) : 0;
            const bTs = b.closedAt ? Date.parse(b.closedAt) : 0;
            return aTs - bTs;
        });

        for (const trade of sorted) {
            const pnl = toNumber(trade.pnlUsdt) ?? 0;
            running += pnl;
            points.push({
                at: trade.closedAt ? new Date(trade.closedAt).toLocaleTimeString() : 'N/A',
                equity: Number(running.toFixed(2)),
            });
        }
        return points;
    }, [closedTrades, startBalance]);

    const outcomeData = useMemo(() => {
        const map = new Map<string, number>();
        for (const trade of closedTrades) {
            const key = trade.closeReason ?? 'UNKNOWN';
            map.set(key, (map.get(key) ?? 0) + 1);
        }
        return Array.from(map.entries()).map(([reason, count]) => ({ reason, count }));
    }, [closedTrades]);

    const rHistogramData = useMemo(() => {
        const buckets = [
            { bucket: '< -1', count: 0 },
            { bucket: '-1 to 0', count: 0 },
            { bucket: '0 to 1', count: 0 },
            { bucket: '1 to 2', count: 0 },
            { bucket: '2 to 3', count: 0 },
            { bucket: '>= 3', count: 0 },
        ];

        for (const trade of closedTrades) {
            const r = toNumber(trade.rMultiple);
            if (r === null) {
                continue;
            }
            if (r < -1) {
                buckets[0].count += 1;
            } else if (r < 0) {
                buckets[1].count += 1;
            } else if (r < 1) {
                buckets[2].count += 1;
            } else if (r < 2) {
                buckets[3].count += 1;
            } else if (r < 3) {
                buckets[4].count += 1;
            } else {
                buckets[5].count += 1;
            }
        }

        return buckets;
    }, [closedTrades]);

    const sideWinrateData = useMemo(() => {
        const bySide: Record<string, { total: number; wins: number }> = {
            LONG: { total: 0, wins: 0 },
            SHORT: { total: 0, wins: 0 },
        };

        for (const trade of closedTrades) {
            const side = trade.side === 'SHORT' ? 'SHORT' : 'LONG';
            bySide[side].total += 1;
            const pnl = toNumber(trade.pnlUsdt) ?? 0;
            if (pnl > 0) {
                bySide[side].wins += 1;
            }
        }

        return ['LONG', 'SHORT'].map((side) => {
            const total = bySide[side].total;
            const wins = bySide[side].wins;
            return {
                side,
                winRate: total > 0 ? Number(((wins / total) * 100).toFixed(2)) : 0,
            };
        });
    }, [closedTrades]);

    const reclaimCohortData = useMemo(() => {
        if (!analytics?.cohorts) {
            return [] as Array<{ bucket: string; winRate: number }>;
        }

        const reclaimKey = Object.keys(analytics.cohorts).find((key) => key.toLowerCase().includes('reclaim'));
        if (!reclaimKey) {
            return [] as Array<{ bucket: string; winRate: number }>;
        }

        return (analytics.cohorts[reclaimKey] ?? []).map((item) => ({
            bucket: item.bucket,
            winRate: normalizedWinRate(item.winRate) * 100,
        }));
    }, [analytics]);

    const metrics = analytics?.metrics ?? {};

    const currentPhaseIndex = useMemo(() => {
        if (!status?.enabled) {
            return -1;
        }
        if (status.cycleRunning) {
            return 3;
        }
        if (status.openPositionsCount > 0) {
            return 5;
        }
        if (status.closedTradesCount > 0) {
            return 6;
        }
        return 1;
    }, [status]);

    const progress = useMemo(() => {
        if (currentPhaseIndex < 0) {
            return 0;
        }
        return ((currentPhaseIndex + 1) / PHASES.length) * 100;
    }, [currentPhaseIndex]);

    const selectedTradeId = routeTradeId ?? null;

    const parentRef = useRef<HTMLDivElement | null>(null);
    const rowVirtualizer = useVirtualizer({
        count: trades.length,
        getScrollElement: () => parentRef.current,
        estimateSize: () => 46,
        overscan: 10,
    });

    return (
        <div className="space-y-6">
            <div className="rounded-xl border border-slate-700 bg-slate-800 p-6 shadow-lg">
                <div className="flex flex-wrap items-center justify-between gap-3">
                    <div>
                        <h1 className="text-3xl font-bold text-white">Demo Trading</h1>
                        <p className="mt-1 text-sm text-slate-300">One-toggle learning mode with live workflow and analytics.</p>
                    </div>

                    <button
                        type="button"
                        onClick={() => void toggleDemo()}
                        disabled={!canToggleDemo || toggleBusy}
                        className={`relative inline-flex h-10 w-28 items-center rounded-full border px-1 transition ${
                            isDemoEnabled
                                ? 'border-emerald-600 bg-emerald-900/40'
                                : 'border-slate-600 bg-slate-700'
                        } ${(!canToggleDemo || toggleBusy) ? 'opacity-60' : ''}`}
                        title={!canToggleDemo ? 'Disabled by permission: demo.enable_disable' : undefined}
                    >
                        <motion.span
                            layout
                            className={`h-8 w-12 rounded-full ${isDemoEnabled ? 'bg-emerald-400' : 'bg-slate-300'}`}
                            transition={{ type: 'spring', stiffness: 500, damping: 30 }}
                        />
                        <span className="absolute right-3 text-xs font-semibold text-slate-100">
                            {toggleBusy ? '...' : `Demo ${isDemoEnabled ? 'ON' : 'OFF'}`}
                        </span>
                    </button>
                </div>

                <p className="mt-3 text-xs text-slate-400">
                    Locked invariants: minRR &gt;= 2, executionTf=15m, biasTf=1h, fractalPeriod=5, maxEquityPctLocked=1.0
                </p>

                {refreshing && (
                    <p className="mt-2 text-xs text-blue-300">Refreshing live data...</p>
                )}
            </div>

            {uiError && (
                <Banner
                    message={`${uiError.errorCode}: ${uiError.message}${uiError.traceId ? ` | traceId=${uiError.traceId}` : ''} | fix: ${uiError.fix}`}
                    onRetry={() => void fetchAll()}
                />
            )}

            {!isDemoEnabled && (
                <div className="rounded-xl border border-slate-700 bg-slate-800 p-8 text-center text-slate-300">
                    <p className="text-lg font-semibold">Demo runtime is paused</p>
                    <p className="mt-1 text-sm">Enable the master toggle to start the automatic demo lifecycle and 5s live updates.</p>
                </div>
            )}

            <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
                <div className="flex items-center justify-between">
                    <h2 className="text-lg font-semibold text-white">Workflow Timeline</h2>
                    <div className="text-xs text-slate-400">cycles={status?.cycleCountTotal ?? 0} open={status?.openPositionsCount ?? 0} closed={status?.closedTradesCount ?? 0}</div>
                </div>

                <div className="mt-3 h-2 w-full rounded bg-slate-700">
                    <motion.div
                        className="h-2 rounded bg-blue-500"
                        animate={{ width: `${progress}%` }}
                        transition={{ duration: 0.35 }}
                    />
                </div>

                <div className="mt-4 grid grid-cols-1 gap-2 md:grid-cols-4 xl:grid-cols-7">
                    {PHASES.map((phase, index) => {
                        const active = currentPhaseIndex >= index;
                        return (
                            <motion.div
                                key={phase}
                                initial={{ opacity: 0, y: 10 }}
                                animate={{ opacity: 1, y: 0 }}
                                transition={{ delay: index * 0.03 }}
                                className={`rounded border px-3 py-2 text-xs ${
                                    active
                                        ? 'border-blue-600/60 bg-blue-900/25 text-blue-100'
                                        : 'border-slate-700 bg-slate-900/40 text-slate-400'
                                }`}
                            >
                                {phase}
                            </motion.div>
                        );
                    })}
                </div>
            </section>

            <section className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
                <KpiCard title="Balance / Equity" value={`${toNumber(status?.account?.balanceUsdt)?.toFixed(2) ?? '0.00'} / ${toNumber(status?.account?.equityUsdt)?.toFixed(2) ?? '0.00'}`} />
                <KpiCard title="Win Rate" value={formatPct(metrics.winRate ?? status?.winRate)} />
                <KpiCard title="Avg R" value={toNumber(metrics.avgWinR ?? metrics.avgR)?.toFixed(3) ?? '0.000'} />
                <KpiCard title="Expectancy" value={toNumber(metrics.expectancyR)?.toFixed(3) ?? '0.000'} />
                <KpiCard title="Profit Factor" value={toNumber(metrics.profitFactor)?.toFixed(3) ?? '0.000'} />
                <KpiCard title="Max Drawdown" value={formatPct(metrics.maxDrawdownPct)} />
                <KpiCard title="Avg Hold" value={`${toNumber(metrics.avgHoldMinutes)?.toFixed(1) ?? '0.0'} min`} />
                <KpiCard title="Trade Activity" value={`Open ${status?.openPositionsCount ?? 0} / Closed ${status?.closedTradesCount ?? 0}`} pulse={pulseOpen || pulseClosed} />
            </section>

            <section className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                <div className="h-72 rounded-xl border border-slate-700 bg-slate-800 p-4 shadow-lg">
                    <h3 className="mb-2 text-sm font-semibold text-slate-100">Equity Curve</h3>
                    <ResponsiveContainer width="100%" height="90%">
                        <LineChart data={equityData}>
                            <CartesianGrid stroke="#334155" strokeDasharray="3 3" />
                            <XAxis dataKey="at" hide />
                            <YAxis stroke="#94a3b8" fontSize={11} />
                            <Tooltip contentStyle={{ backgroundColor: '#0f172a', borderColor: '#334155', borderRadius: '8px' }} />
                            <Line type="monotone" dataKey="equity" stroke="#22c55e" strokeWidth={2} dot={false} />
                        </LineChart>
                    </ResponsiveContainer>
                </div>

                <div className="h-72 rounded-xl border border-slate-700 bg-slate-800 p-4 shadow-lg">
                    <h3 className="mb-2 text-sm font-semibold text-slate-100">Outcome Distribution</h3>
                    <ResponsiveContainer width="100%" height="90%">
                        <BarChart data={outcomeData}>
                            <CartesianGrid stroke="#334155" strokeDasharray="3 3" />
                            <XAxis dataKey="reason" stroke="#94a3b8" fontSize={11} />
                            <YAxis stroke="#94a3b8" fontSize={11} allowDecimals={false} />
                            <Tooltip contentStyle={{ backgroundColor: '#0f172a', borderColor: '#334155', borderRadius: '8px' }} />
                            <Bar dataKey="count" fill="#60a5fa" radius={[4, 4, 0, 0]} />
                        </BarChart>
                    </ResponsiveContainer>
                </div>

                <div className="h-72 rounded-xl border border-slate-700 bg-slate-800 p-4 shadow-lg">
                    <h3 className="mb-2 text-sm font-semibold text-slate-100">R-Multiple Histogram</h3>
                    <ResponsiveContainer width="100%" height="90%">
                        <BarChart data={rHistogramData}>
                            <CartesianGrid stroke="#334155" strokeDasharray="3 3" />
                            <XAxis dataKey="bucket" stroke="#94a3b8" fontSize={11} />
                            <YAxis stroke="#94a3b8" fontSize={11} allowDecimals={false} />
                            <Tooltip contentStyle={{ backgroundColor: '#0f172a', borderColor: '#334155', borderRadius: '8px' }} />
                            <Bar dataKey="count" fill="#f59e0b" radius={[4, 4, 0, 0]} />
                        </BarChart>
                    </ResponsiveContainer>
                </div>

                <div className="h-72 rounded-xl border border-slate-700 bg-slate-800 p-4 shadow-lg">
                    <h3 className="mb-2 text-sm font-semibold text-slate-100">Win Rate by Side</h3>
                    <ResponsiveContainer width="100%" height="90%">
                        <BarChart data={sideWinrateData}>
                            <CartesianGrid stroke="#334155" strokeDasharray="3 3" />
                            <XAxis dataKey="side" stroke="#94a3b8" fontSize={11} />
                            <YAxis stroke="#94a3b8" fontSize={11} domain={[0, 100]} />
                            <Tooltip formatter={(value) => [`${Number(value).toFixed(2)}%`, 'Win Rate']} contentStyle={{ backgroundColor: '#0f172a', borderColor: '#334155', borderRadius: '8px' }} />
                            <Bar dataKey="winRate" fill="#34d399" radius={[4, 4, 0, 0]} />
                        </BarChart>
                    </ResponsiveContainer>
                </div>

                {reclaimCohortData.length > 0 && (
                    <div className="h-72 rounded-xl border border-slate-700 bg-slate-800 p-4 shadow-lg xl:col-span-2">
                        <h3 className="mb-2 text-sm font-semibold text-slate-100">Reclaim Strength Cohort Win Rate</h3>
                        <ResponsiveContainer width="100%" height="90%">
                            <BarChart data={reclaimCohortData}>
                                <CartesianGrid stroke="#334155" strokeDasharray="3 3" />
                                <XAxis dataKey="bucket" stroke="#94a3b8" fontSize={11} />
                                <YAxis stroke="#94a3b8" fontSize={11} domain={[0, 100]} />
                                <Tooltip formatter={(value) => [`${Number(value).toFixed(2)}%`, 'Win Rate']} contentStyle={{ backgroundColor: '#0f172a', borderColor: '#334155', borderRadius: '8px' }} />
                                <Bar dataKey="winRate" fill="#c084fc" radius={[4, 4, 0, 0]} />
                            </BarChart>
                        </ResponsiveContainer>
                    </div>
                )}
            </section>

            <section className="rounded-xl border border-slate-700 bg-slate-800 p-5 shadow-lg">
                <h2 className="text-lg font-semibold text-white">Trades</h2>
                <p className="mt-1 text-xs text-slate-400">Virtualized list. Click a trade to open details, snapshot JSON, and close reason diagnostics.</p>

                <div className="mt-4 rounded border border-slate-700 bg-slate-900/60">
                    <div className="grid grid-cols-[150px_120px_100px_130px_120px_100px_120px] border-b border-slate-700 px-3 py-2 text-xs uppercase tracking-wide text-slate-400">
                        <span>Time</span>
                        <span>Symbol</span>
                        <span>Side</span>
                        <span>Result</span>
                        <span className="text-right">PnL</span>
                        <span className="text-right">R</span>
                        <span className="text-right">Duration</span>
                    </div>

                    <div ref={parentRef} className="h-[360px] overflow-auto">
                        <div
                            style={{
                                height: `${rowVirtualizer.getTotalSize()}px`,
                                width: '100%',
                                position: 'relative',
                            }}
                        >
                            {rowVirtualizer.getVirtualItems().map((virtualRow) => {
                                const trade = trades[virtualRow.index];
                                if (!trade) {
                                    return null;
                                }

                                return (
                                    <div
                                        key={trade.id}
                                        onClick={() => navigate(`/demo/trades/${trade.id}`)}
                                        className="absolute left-0 top-0 grid w-full cursor-pointer grid-cols-[150px_120px_100px_130px_120px_100px_120px] items-center px-3 text-sm text-slate-200 hover:bg-slate-700/40"
                                        style={{
                                            height: `${virtualRow.size}px`,
                                            transform: `translateY(${virtualRow.start}px)`,
                                        }}
                                    >
                                        <span>{formatDateTime(trade.openedAt ?? trade.closedAt)}</span>
                                        <span className="font-semibold text-white">{trade.symbol}</span>
                                        <span className={trade.side === 'LONG' ? 'text-emerald-300' : 'text-rose-300'}>{trade.side}</span>
                                        <span>{trade.closeReason ?? trade.status}</span>
                                        <span className="text-right font-mono">{formatSignedMoney(trade.pnlUsdt)}</span>
                                        <span className="text-right font-mono">{formatSignedR(trade.rMultiple)}</span>
                                        <span className="text-right">{formatTimeInTrade(trade.openedAt, trade.closedAt)}</span>
                                    </div>
                                );
                            })}
                        </div>
                    </div>
                </div>
            </section>

            <DemoAiSuggestionsCard
                suggestions={suggestions}
                loading={loading}
                actionLoading={actionBusy !== null}
                onAcceptClick={() => {
                    if (!canAiAction) {
                        return;
                    }
                    setShowAcceptModal(true);
                }}
                onRejectClick={() => {
                    void handleRejectBatch();
                }}
                actionsDisabled={!canAiAction}
                actionsDisabledReason={!canAiAction ? 'Disabled by operator permission: demo.ai.accept_reject' : undefined}
            />

            <DemoAcceptModal
                isOpen={showAcceptModal}
                loading={actionBusy === 'accept'}
                onCancel={() => setShowAcceptModal(false)}
                onConfirm={() => {
                    void handleAcceptBatch();
                }}
            />

            <DemoTradeDetailDrawer tradeId={selectedTradeId} onClose={() => navigate('/demo')} />
        </div>
    );
}
