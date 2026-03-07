import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
    AlertTriangle,
    ArrowLeft,
    CheckCircle,
    CircleSlash,
    Clock3,
    RefreshCw,
    ShieldAlert,
} from 'lucide-react';
import { getRecommendation, type RecommendationDTO } from '../api/client';
import type { LiveTradeExecutionDTO, LiveTradingPreflightDTO } from '../api/liveTradingApi';
import { JsonBlock } from '../components/JsonBlock';
import { FeedbackForm } from '../components/FeedbackForm';
import { BinanceFillGuide } from '../components/BinanceFillGuide';
import { Banner } from '../components/Banner';
import { LiveExecutionConfirmModal } from '../components/LiveExecutionConfirmModal';
import { useRecommendationExecution } from '../hooks/useRecommendationExecution';
import { usePermissions } from '../hooks/usePermissions';
import { journalStore, type JournalItem } from '../store/journalStore';
import { parseApiError } from '../utils/apiError';

function formatInstant(value: string | null | undefined): string {
    if (!value) {
        return 'n/a';
    }
    return new Date(value).toLocaleString();
}

function formatMaybe(value: unknown): string {
    if (value == null || value === '') {
        return 'n/a';
    }
    return String(value);
}

function statusTone(state: string): string {
    if (state === 'RECONCILED' || state === 'OPEN' || state === 'DRY_RUN') {
        return 'border-emerald-700/50 bg-emerald-900/25 text-emerald-200';
    }
    if (state === 'BLOCKED' || state === 'FAILED' || state === 'PROTECTION_FAILED' || state === 'EMERGENCY_CLOSE_FAILED') {
        return 'border-rose-700/50 bg-rose-900/30 text-rose-200';
    }
    if (state === 'PENDING_RECONCILE') {
        return 'border-amber-700/50 bg-amber-900/30 text-amber-200';
    }
    return 'border-sky-700/50 bg-sky-900/25 text-sky-200';
}

function postureLabel(preflight: LiveTradingPreflightDTO | null): string {
    if (!preflight) {
        return 'UNAVAILABLE';
    }
    if (!preflight.runtime.liveExecutionEnabled) {
        return 'DISABLED';
    }
    if (preflight.runtime.readOnly) {
        return 'READ ONLY';
    }
    return preflight.executable ? 'READY' : 'BLOCKED';
}

function actionLabel(): string {
    return 'Open Real Order on Binance';
}

function firstBlockedReason(preflight: LiveTradingPreflightDTO | null): string | null {
    if (!preflight || preflight.blockedReasons.length === 0) {
        return null;
    }
    return preflight.blockedReasons[0].message;
}

function statusText(value: boolean | null | undefined, yes = 'PASS', no = 'BLOCKED', unknown = 'n/a'): string {
    if (value == null) {
        return unknown;
    }
    return value ? yes : no;
}

function createClientRequestId(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID();
    }
    return `manual-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function ExecutionCard({ execution }: { execution: LiveTradeExecutionDTO }) {
    return (
        <div className="mt-5 rounded-xl border border-slate-700 bg-slate-950/60 p-5">
            <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                    <h3 className="text-lg font-semibold text-white">Latest Execution Attempt</h3>
                    <p className="mt-1 text-sm text-slate-400">
                        Triggered via manual button only. Backend state is authoritative for Binance confirmation.
                    </p>
                </div>
                <span className={`rounded-full border px-3 py-1 text-xs font-semibold uppercase tracking-wider ${statusTone(execution.executionState)}`}>
                    {execution.executionState}
                </span>
            </div>

            <div className="mt-4 grid gap-3 md:grid-cols-4">
                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3">
                    <p className="text-xs uppercase tracking-wider text-slate-400">Mode</p>
                    <p className={`mt-2 text-sm font-semibold ${execution.dryRun ? 'text-amber-300' : 'text-red-300'}`}>
                        {execution.dryRun ? 'DRY RUN' : 'REAL'}
                    </p>
                </div>
                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3">
                    <p className="text-xs uppercase tracking-wider text-slate-400">Created</p>
                    <p className="mt-2 text-sm text-white">{formatInstant(execution.createdAt)}</p>
                </div>
                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3">
                    <p className="text-xs uppercase tracking-wider text-slate-400">Last Reconciled</p>
                    <p className="mt-2 text-sm text-white">{formatInstant(execution.lastReconciledAt)}</p>
                </div>
                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3">
                    <p className="text-xs uppercase tracking-wider text-slate-400">Trace ID</p>
                    <p className="mt-2 break-all font-mono text-xs text-slate-200">{execution.traceId ?? 'n/a'}</p>
                </div>
            </div>

            <div className="mt-4 grid gap-3 md:grid-cols-2">
                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3">
                    <p className="text-xs uppercase tracking-wider text-slate-400">Binance Order IDs</p>
                    <div className="mt-2 space-y-1 text-sm text-slate-200">
                        <p>Entry: {formatMaybe(execution.orderRefs.entryOrderId)}</p>
                        <p>Stop Loss: {formatMaybe(execution.orderRefs.slOrderId)}</p>
                        <p>Take Profit: {formatMaybe(execution.orderRefs.tpOrderId)}</p>
                        <p>Emergency Close: {formatMaybe(execution.orderRefs.emergencyCloseOrderId)}</p>
                    </div>
                </div>
                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3">
                    <p className="text-xs uppercase tracking-wider text-slate-400">Client Order IDs</p>
                    <div className="mt-2 space-y-1 break-all font-mono text-xs text-slate-200">
                        <p>{execution.orderRefs.entryClientOrderId ?? 'n/a'}</p>
                        <p>{execution.orderRefs.slClientOrderId ?? 'n/a'}</p>
                        <p>{execution.orderRefs.tpClientOrderId ?? 'n/a'}</p>
                        <p>{execution.orderRefs.emergencyCloseClientOrderId ?? 'n/a'}</p>
                    </div>
                </div>
            </div>

            {(execution.errorCode || execution.errorMessage) && (
                <div className="mt-4 rounded-lg border border-rose-700/50 bg-rose-900/20 p-3 text-sm text-rose-200">
                    <p className="font-semibold">{execution.errorCode ?? 'EXECUTION_ERROR'}</p>
                    <p className="mt-1">{execution.errorMessage ?? 'Execution failed.'}</p>
                </div>
            )}

            {execution.events.length > 0 && (
                <div className="mt-4 rounded-lg border border-slate-800 bg-slate-900/60 p-3">
                    <p className="text-xs uppercase tracking-wider text-slate-400">Execution Timeline</p>
                    <div className="mt-3 space-y-3">
                        {execution.events.map((event) => (
                            <div key={`${event.id ?? event.createdAt}-${event.eventType}`} className="flex items-start gap-3 border-l border-slate-700 pl-3">
                                <div className="mt-1 h-2 w-2 rounded-full bg-slate-500" />
                                <div>
                                    <p className="text-sm font-semibold text-slate-100">
                                        {event.eventType.replaceAll('_', ' ')}
                                    </p>
                                    <p className="text-xs text-slate-400">{formatInstant(event.createdAt)}</p>
                                    <p className="mt-1 text-sm text-slate-300">{event.message}</p>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            )}
        </div>
    );
}

export const RecommendationDetailPage: React.FC = () => {
    const navigate = useNavigate();
    const { id } = useParams<{ id: string }>();
    const { loading: permissionsLoading } = usePermissions();
    const [data, setData] = useState<RecommendationDTO | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [journalEntry, setJournalEntry] = useState<JournalItem | null>(null);
    const [saved, setSaved] = useState(false);
    const [manualPlacementLocked, setManualPlacementLocked] = useState(true);
    const [manualPlacementLockReason, setManualPlacementLockReason] = useState(
        'Live MARK preflight pending. Refresh mark before placing.',
    );
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [pendingClientRequestId, setPendingClientRequestId] = useState<string | null>(null);
    const [operatorNote, setOperatorNote] = useState('');

    const {
        preflight,
        execution,
        history,
        loading: liveExecutionLoading,
        executing,
        actionError,
        refresh: refreshExecution,
        executeLive,
        clearActionError,
    } = useRecommendationExecution(id, Boolean(id));

    const syncJournalState = (recId: string) => {
        const items = journalStore.list();
        const item = items.find((entry) => entry.id === recId) || null;
        setJournalEntry(item);
    };

    useEffect(() => {
        const fetchRec = async () => {
            if (!id) {
                return;
            }
            try {
                const res = await getRecommendation(id);
                setData(res);
                setError(null);
                journalStore.markAsOpen(id);
                syncJournalState(id);
            } catch (fetchError) {
                const parsed = parseApiError(fetchError);
                setError(parsed.message || 'Failed to load recommendation details.');
            } finally {
                setLoading(false);
            }
        };
        void fetchRec();
    }, [id]);

    const handleFeedbackSuccess = (result: 'WIN' | 'LOSS', pnlUsdt?: number, rMultiple?: number) => {
        if (id) {
            journalStore.updateFeedbackStatus(id, result, pnlUsdt, rMultiple);
            syncJournalState(id);
        }
        setSaved(true);
        window.setTimeout(() => setSaved(false), 3_000);
    };

    const handleLockStateChange = (locked: boolean, reason: string) => {
        setManualPlacementLocked(locked);
        setManualPlacementLockReason(reason);
    };

    const buttonDisabledReason = useMemo(() => {
        if (permissionsLoading) {
            return 'Loading permission state.';
        }
        if (liveExecutionLoading) {
            return 'Loading live execution state.';
        }
        if (!preflight) {
            return 'Live execution preflight is unavailable.';
        }
        return firstBlockedReason(preflight);
    }, [liveExecutionLoading, permissionsLoading, preflight]);

    const runtimeLabel = postureLabel(preflight);

    const handleOpenExecutionConfirm = () => {
        clearActionError();
        setPendingClientRequestId((current) => current ?? createClientRequestId());
        setConfirmOpen(true);
    };

    const handleConfirmExecution = async () => {
        if (!pendingClientRequestId) {
            return;
        }
        try {
            await executeLive({
                clientRequestId: pendingClientRequestId,
                operatorNote: operatorNote.trim() || undefined,
            });
            setConfirmOpen(false);
            setOperatorNote('');
            setPendingClientRequestId(null);
        } catch {
            // Structured error is shown from the hook state.
        }
    };

    const handleCancelExecution = () => {
        if (executing) {
            return;
        }
        setConfirmOpen(false);
        setOperatorNote('');
        setPendingClientRequestId(null);
    };

    if (loading) {
        return <div className="animate-pulse p-8 text-center text-gray-400">Loading execution details...</div>;
    }

    if (!data) {
        return (
            <div className="p-8 text-center text-gray-400">
                {error && <div className="mb-4"><Banner message={error} /></div>}
                <p>No recommendation found.</p>
                <button onClick={() => navigate(-1)} className="mt-4 text-blue-400 hover:text-blue-300">
                    Go Back
                </button>
            </div>
        );
    }

    return (
        <div className="mx-auto max-w-5xl p-6">
            <button
                onClick={() => navigate(-1)}
                className="mb-6 flex items-center text-gray-400 transition-colors hover:text-white"
            >
                <ArrowLeft size={16} className="mr-2" /> Back to Dashboard
            </button>

            <div className="mb-6 rounded-lg border border-gray-700 bg-gray-800 p-6 shadow-xl">
                <div className="mb-6 flex items-start justify-between">
                    <div>
                        <h1 className="text-3xl font-black tracking-widest text-white">{data.symbol}</h1>
                        <p className="mt-1 text-gray-400">{data.rationaleText}</p>
                    </div>
                    <div className="text-right">
                        <span className={`rounded px-4 py-2 font-bold ${data.side === 'BUY' ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'
                            }`}>
                            {data.side}
                        </span>
                    </div>
                </div>

                <div className="mb-8 grid grid-cols-2 gap-4 lg:grid-cols-4">
                    <div className="rounded border border-gray-700 bg-gray-900 p-4 text-center">
                        <p className="mb-1 text-xs uppercase tracking-widest text-gray-500">Leverage</p>
                        <p className="font-mono text-lg text-white">{data.leverageRecommendation}x</p>
                    </div>
                    <div className="rounded border border-gray-700 bg-gray-900 p-4 text-center">
                        <p className="mb-1 text-xs uppercase tracking-widest text-gray-500">Margin Mode</p>
                        <p className="font-mono text-lg text-white">{data.marginMode}</p>
                    </div>
                    <div className="rounded border border-gray-700 bg-gray-900 p-4 text-center">
                        <p className="mb-1 text-xs uppercase tracking-widest text-gray-500">Position</p>
                        <p className="font-mono text-lg text-white">{data.positionMode}</p>
                    </div>
                    <div className="rounded border border-gray-700 bg-gray-900 p-4 text-center">
                        <p className="mb-1 text-xs uppercase tracking-widest text-gray-500">Quantity</p>
                        <p className="font-mono text-lg text-yellow-400">{data.entryOrder?.quantity || 'N/A'}</p>
                    </div>
                </div>

                <div className="rounded border border-yellow-700/50 bg-yellow-900/30 p-4">
                    <div className="flex items-start gap-3">
                        <AlertTriangle className="mt-1 shrink-0 text-yellow-500" size={20} />
                        <div>
                            <h4 className="font-bold text-yellow-300">Recommendation Review</h4>
                            <p className="mt-1 text-sm text-yellow-100/75">
                                Scan, autoscan, and recommendation generation still do not place live Binance orders.
                                The payloads below remain the manual review path. Real execution is only possible from the
                                dedicated live-execution panel after explicit confirmation.
                            </p>
                        </div>
                    </div>
                </div>

                <h2 className="mt-8 text-xl font-bold text-gray-200">API Order Payloads</h2>
                <p className="mt-1 text-sm text-gray-400">
                    Manual guidance only. These JSON blocks do nothing until an operator uses them or clicks the
                    separate live-execution button.
                </p>
                <div className="mt-4 grid grid-cols-1 gap-6 lg:grid-cols-3">
                    <JsonBlock
                        label="Entry (MARKET)"
                        data={data.entryOrder}
                        copyDisabled={manualPlacementLocked}
                        copyDisabledTitle={manualPlacementLockReason}
                        lockMessage={manualPlacementLocked ? manualPlacementLockReason : undefined}
                    />
                    <JsonBlock
                        label="Stop Loss (STOP_MARKET)"
                        data={data.slOrder}
                        copyDisabled={manualPlacementLocked}
                        copyDisabledTitle={manualPlacementLockReason}
                        lockMessage={manualPlacementLocked ? manualPlacementLockReason : undefined}
                    />
                    <JsonBlock
                        label="Take Profit (TAKE_PROFIT_MARKET)"
                        data={data.tpOrder}
                        copyDisabled={manualPlacementLocked}
                        copyDisabledTitle={manualPlacementLockReason}
                        lockMessage={manualPlacementLocked ? manualPlacementLockReason : undefined}
                    />
                </div>

                <BinanceFillGuide rec={data} onLockStateChange={handleLockStateChange} />

                <div className="mt-10 rounded-xl border border-red-800/40 bg-slate-950/70 p-6">
                    <div className="flex flex-wrap items-start justify-between gap-3">
                        <div>
                            <h2 className="text-2xl font-bold text-white">Real Binance Execution</h2>
                            <p className="mt-1 text-sm text-slate-400">
                                This is the only backend path that can submit a real Binance Futures order, and it only
                                runs after an explicit operator click plus server-side preflight.
                            </p>
                        </div>
                        <span className={`rounded-full border px-3 py-1 text-xs font-semibold uppercase tracking-wider ${runtimeLabel.includes('READY')
                            ? 'border-emerald-700/50 bg-emerald-900/20 text-emerald-200'
                            : runtimeLabel === 'UNAVAILABLE'
                                ? 'border-slate-700 bg-slate-900/50 text-slate-300'
                                : 'border-amber-700/50 bg-amber-900/20 text-amber-200'
                            }`}>
                            {runtimeLabel}
                        </span>
                    </div>

                    <div className="mt-4 rounded-lg border border-red-700/60 bg-red-950/30 p-4 text-sm text-red-100">
                        <div className="flex items-start gap-3">
                            <ShieldAlert className="mt-0.5 shrink-0 text-red-300" size={18} />
                            <div>
                                <p className="font-semibold text-red-200">High-risk live trading warning</p>
                                <p className="mt-1">
                                    If preflight passes, clicking the execute button can place a real Binance Futures
                                    order. No success state is shown until the backend persists the Binance response.
                                </p>
                            </div>
                        </div>
                    </div>

                    {actionError && (
                        <div className="mt-4">
                            <Banner
                                message={`${actionError.errorCode}: ${actionError.message}${actionError.traceId ? ` | traceId=${actionError.traceId}` : ''}`}
                                onRetry={() => {
                                    clearActionError();
                                    void refreshExecution();
                                }}
                            />
                        </div>
                    )}

                    {(permissionsLoading || liveExecutionLoading) && (
                        <div className="mt-4 rounded-lg border border-slate-700 bg-slate-900/60 p-4 text-sm text-slate-300">
                            Loading live execution state...
                        </div>
                    )}

                    {preflight && (
                        <>
                            <div className="mt-4 grid gap-3 md:grid-cols-4">
                                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3">
                                    <p className="text-xs uppercase tracking-wider text-slate-400">Capability</p>
                                    <p className={`mt-2 text-sm font-semibold ${preflight.runtime.liveExecutionEnabled ? 'text-emerald-300' : 'text-rose-300'}`}>
                                        {preflight.runtime.liveExecutionEnabled ? 'ENABLED' : 'DISABLED'}
                                    </p>
                                </div>
                                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3">
                                    <p className="text-xs uppercase tracking-wider text-slate-400">Last Checked</p>
                                    <p className="mt-2 text-sm text-white">
                                        <Clock3 size={14} className="mr-1 inline text-slate-500" />
                                        {formatInstant(preflight.checkedAt)}
                                    </p>
                                </div>
                                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3">
                                    <p className="text-xs uppercase tracking-wider text-slate-400">Trading Gate</p>
                                    <p className={`mt-2 text-sm font-semibold ${preflight.runtime.readOnly ? 'text-amber-300' : 'text-emerald-300'}`}>
                                        {preflight.runtime.readOnly ? 'READ ONLY' : 'LIVE'}
                                    </p>
                                </div>
                                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3">
                                    <p className="text-xs uppercase tracking-wider text-slate-400">Local Request</p>
                                    <p className={`mt-2 text-sm font-semibold ${preflight.localRequest.allowed ? 'text-emerald-300' : 'text-rose-300'}`}>
                                        {preflight.localRequest.allowed ? 'LOCALHOST OK' : 'BLOCKED'}
                                    </p>
                                </div>
                            </div>

                            <div className="mt-4 grid gap-3 md:grid-cols-4">
                                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3 text-sm text-slate-200">
                                    <p className="text-xs uppercase tracking-wider text-slate-400">Preflight Summary</p>
                                    <p className="mt-2">Executable: {preflight.executable ? 'YES' : 'NO'}</p>
                                    <p>Capability: {preflight.runtime.liveExecutionEnabled ? 'ON' : 'OFF'}</p>
                                    <p>Read Only: {preflight.runtime.readOnly ? 'YES' : 'NO'}</p>
                                    <p>Duplicate active execution: {preflight.runtime.duplicateSubmitBlocked ? 'YES' : 'NO'}</p>
                                    <p>Recommendation stale: {preflight.runtime.recommendationStale ? 'YES' : 'NO'}</p>
                                    <p>Blocked reasons: {preflight.blockedReasons.length}</p>
                                </div>
                                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3 text-sm text-slate-200">
                                    <p className="text-xs uppercase tracking-wider text-slate-400">Binance Diagnostics</p>
                                    <p className="mt-2">Auth: {statusText(preflight.binance.authValid, 'VALID', 'INVALID')}</p>
                                    <p>Futures order read: {statusText(preflight.binance.futuresOrderReadOk)}</p>
                                    <p>Position mode read: {statusText(preflight.binance.positionModeReadOk)}</p>
                                    <p>Endpoint family: {formatMaybe(preflight.binance.endpointFamily)}</p>
                                    <p>Primary blocker: {preflight.binance.blockerCode ?? 'n/a'}</p>
                                </div>
                                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3 text-sm text-slate-200">
                                    <p className="text-xs uppercase tracking-wider text-slate-400">Binance Policy</p>
                                    <p className="mt-2">Futures permission: {statusText(preflight.binance.futuresPermissionOk)}</p>
                                    <p>IP allowlist: {statusText(preflight.binance.ipAllowlistOk)}</p>
                                    <p>Timestamp: {statusText(preflight.binance.timestampOk)}</p>
                                    <p>Signing: {statusText(preflight.binance.signingOk)}</p>
                                    <p>Request IP hint: {preflight.binance.requestIpHint ?? 'n/a'}</p>
                                </div>
                                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3 text-sm text-slate-200">
                                    <p className="text-xs uppercase tracking-wider text-slate-400">Exchange Preview</p>
                                    <p className="mt-2">Mark: {formatMaybe(preflight.exchangeValidation.markPrice)}</p>
                                    <p>Tick: {formatMaybe(preflight.exchangeValidation.tickSize)}</p>
                                    <p>Step: {formatMaybe(preflight.exchangeValidation.stepSize)}</p>
                                    <p>Notional: {formatMaybe(preflight.exchangeValidation.entryNotionalUsdt)}</p>
                                </div>
                            </div>

                            <div className="mt-4 grid gap-3 md:grid-cols-3">
                                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3 text-sm text-slate-200">
                                    <p className="text-xs uppercase tracking-wider text-slate-400">Placeability</p>
                                    <p className="mt-2">Status: {statusText(preflight.placeabilityOk, 'PASS', 'BLOCKED')}</p>
                                    <p>Reason: {preflight.placeability?.reasonText ?? 'n/a'}</p>
                                </div>
                                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3 text-sm text-slate-200">
                                    <p className="text-xs uppercase tracking-wider text-slate-400">Protection Preview</p>
                                    <p className="mt-2">SL: {formatMaybe(preflight.exchangeValidation.slStopPrice)}</p>
                                    <p>TP: {formatMaybe(preflight.exchangeValidation.tpStopPrice)}</p>
                                    <p>Margin: {formatMaybe(preflight.exchangeValidation.marginMode)}</p>
                                    <p>Position: {formatMaybe(preflight.exchangeValidation.positionMode)}</p>
                                </div>
                                <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-3 text-sm text-slate-200">
                                    <p className="text-xs uppercase tracking-wider text-slate-400">Timestamp / Locality</p>
                                    <p className="mt-2">Skew: {formatMaybe(preflight.binance.timestampSkewMs)}</p>
                                    <p>RecvWindow: {formatMaybe(preflight.binance.recvWindowMs)}</p>
                                    <p>Origin: {preflight.localRequest.origin ?? 'n/a'}</p>
                                    <p>Remote: {preflight.localRequest.remoteAddress ?? 'n/a'}</p>
                                    <p>Local failure: {preflight.localRequest.failureReason ?? 'n/a'}</p>
                                </div>
                            </div>

                            {preflight.binance.blockerMessage && (
                                <div className="mt-4 rounded-lg border border-sky-700/50 bg-sky-900/20 p-4 text-sm text-sky-100">
                                    <p className="font-semibold text-sky-200">Primary Binance Diagnostic</p>
                                    <p className="mt-2 font-mono text-xs text-sky-300">{preflight.binance.blockerCode ?? 'BINANCE'}</p>
                                    <p className="mt-1">{preflight.binance.blockerMessage}</p>
                                </div>
                            )}

                            {preflight.exchangeValidation.failures.length > 0 && (
                                <div className="mt-4 rounded-lg border border-rose-700/50 bg-rose-900/20 p-4 text-sm text-rose-100">
                                    <p className="font-semibold text-rose-200">Exchange Filter Failures</p>
                                    <div className="mt-2 space-y-1">
                                        {preflight.exchangeValidation.failures.map((failure) => (
                                            <p key={failure}>{failure}</p>
                                        ))}
                                    </div>
                                </div>
                            )}

                            {preflight.blockedReasons.length > 0 && (
                                <div className="mt-4 rounded-lg border border-amber-700/50 bg-amber-900/20 p-4 text-sm text-amber-100">
                                    <div className="flex items-start gap-3">
                                        <CircleSlash className="mt-0.5 shrink-0 text-amber-300" size={18} />
                                        <div>
                                            <p className="font-semibold text-amber-200">Blocked Reasons</p>
                                            <div className="mt-2 space-y-2">
                                                {preflight.blockedReasons.map((reason) => (
                                                    <div key={`${reason.code}-${reason.message}`}>
                                                        <p className="font-mono text-xs text-amber-300">{reason.code}</p>
                                                        {reason.source && (
                                                            <p className="text-[11px] uppercase tracking-wider text-amber-400/80">{reason.source}</p>
                                                        )}
                                                        <p>{reason.message}</p>
                                                    </div>
                                                ))}
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            )}

                            <div className="mt-5 flex flex-wrap gap-3">
                                <button
                                    type="button"
                                    onClick={handleOpenExecutionConfirm}
                                    disabled={Boolean(buttonDisabledReason) || executing}
                                    className="rounded-lg border border-red-700/70 bg-red-700 px-4 py-2 text-sm font-semibold text-white hover:bg-red-600 disabled:cursor-not-allowed disabled:opacity-50"
                                >
                                    {executing ? 'Submitting...' : actionLabel()}
                                </button>
                                <button
                                    type="button"
                                    onClick={() => void refreshExecution()}
                                    className="inline-flex items-center gap-2 rounded-lg border border-slate-700 bg-slate-900 px-4 py-2 text-sm font-semibold text-slate-200 hover:bg-slate-800"
                                >
                                    <RefreshCw size={14} />
                                    Refresh Live Status
                                </button>
                            </div>

                            {buttonDisabledReason && (
                                <p className="mt-3 text-sm text-amber-200">
                                    Execute button disabled: {buttonDisabledReason}
                                </p>
                            )}

                            {execution && <ExecutionCard execution={execution} />}

                            {history.length > 0 && (
                                <div className="mt-5 rounded-xl border border-slate-700 bg-slate-950/60 p-4">
                                    <p className="text-sm font-semibold text-white">Recent Execution Attempts</p>
                                    <div className="mt-3 space-y-2">
                                        {history.slice(0, 5).map((item) => (
                                            <div key={item.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-slate-800 bg-slate-900/60 px-3 py-2 text-sm">
                                                <div>
                                                    <p className="font-mono text-xs text-slate-300">{item.id}</p>
                                                    <p className="mt-1 text-slate-400">{formatInstant(item.createdAt)}</p>
                                                </div>
                                                <div className="flex items-center gap-2">
                                                    <span className={`rounded-full border px-2 py-1 text-[11px] font-semibold uppercase tracking-wider ${statusTone(item.executionState)}`}>
                                                        {item.executionState}
                                                    </span>
                                                    <span className={`rounded-full border px-2 py-1 text-[11px] font-semibold uppercase tracking-wider ${item.dryRun
                                                        ? 'border-amber-700/50 bg-amber-900/20 text-amber-200'
                                                        : 'border-red-700/50 bg-red-900/20 text-red-200'
                                                        }`}>
                                                        {item.dryRun ? 'DRY RUN' : 'REAL'}
                                                    </span>
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            )}
                        </>
                    )}
                </div>

                {saved && (
                    <div className="mt-6 flex items-center rounded-lg border border-green-600/50 bg-green-900/40 p-4 text-green-300">
                        <CheckCircle size={18} className="mr-2 shrink-0" />
                        Trade result saved successfully.
                    </div>
                )}
                {journalEntry && journalEntry.status === 'CLOSED' ? (
                    <div className="mt-8 rounded-lg border border-gray-700 bg-gray-900 p-6 shadow-xl">
                        <h3 className="mb-4 text-lg font-bold text-white">Trade Result</h3>
                        <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                            <div>
                                <p className="text-sm text-gray-500">Outcome</p>
                                <p className={`text-xl font-bold ${journalEntry.feedbackResult === 'WIN' ? 'text-green-500' : 'text-red-500'}`}>
                                    {journalEntry.feedbackResult}
                                </p>
                            </div>
                            <div>
                                <p className="text-sm text-gray-500">PnL (USDT)</p>
                                <p className="font-mono text-lg text-white">{journalEntry.feedbackPnl !== undefined ? journalEntry.feedbackPnl : 'N/A'}</p>
                            </div>
                            <div>
                                <p className="text-sm text-gray-500">R-Multiple</p>
                                <p className="font-mono text-lg text-white">{journalEntry.feedbackR !== undefined ? journalEntry.feedbackR : 'N/A'}</p>
                            </div>
                        </div>
                    </div>
                ) : (
                    id && <FeedbackForm recommendationId={id} onSuccess={handleFeedbackSuccess} />
                )}
            </div>

            <LiveExecutionConfirmModal
                isOpen={confirmOpen}
                loading={executing}
                recommendation={data}
                preflight={preflight}
                operatorNote={operatorNote}
                onOperatorNoteChange={setOperatorNote}
                onConfirm={handleConfirmExecution}
                onCancel={handleCancelExecution}
            />
        </div>
    );
};
