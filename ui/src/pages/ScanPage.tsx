import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Play } from 'lucide-react';
import { useLiveScan } from '../hooks/useLiveScan';
import { getLatestScan } from '../api/scan';
import { runScanOnce } from '../api/client';
import { ScanHeaderCard } from '../components/scan/ScanHeaderCard';
import { PhaseTimeline } from '../components/scan/PhaseTimeline';
import { ProgressBarCard } from '../components/scan/ProgressBarCard';
import { ChartsGrid } from '../components/scan/ChartsGrid';
import { EvaluationsTable } from '../components/scan/EvaluationsTable';
import { FilterBar } from '../components/scan/FilterBar';
import type { FilterDecision, SortField } from '../components/scan/FilterBar';
import { CoinDetailDrawer } from '../components/scan/CoinDetailDrawer';
import { usePermissions } from '../hooks/usePermissions';
import { disabledByPermissionTooltip } from '../utils/permissionUi';
import { parseApiError } from '../utils/apiError';

function toUiErrorMessage(error: unknown, fallback: string): string {
    const parsed = parseApiError(error);
    const message = parsed.message || fallback;
    const traceSuffix = parsed.traceId ? ` (trace ${parsed.traceId})` : '';
    if (parsed.errorCode && parsed.errorCode !== 'INTERNAL') {
        return `${fallback}: [${parsed.errorCode}] ${message}${traceSuffix}`;
    }
    return `${fallback}: ${message}${traceSuffix}`;
}

export function ScanPage() {
    const { can } = usePermissions();
    const { scanRunId: routeScanRunId } = useParams<{ scanRunId: string }>();
    const [searchParams] = useSearchParams();
    const navigate = useNavigate();
    const autoRunTriggeredRef = useRef(false);

    const [resolvedScanId, setResolvedScanId] = useState<string | null>(routeScanRunId ?? null);
    const [runStarting, setRunStarting] = useState(false);
    const [runError, setRunError] = useState<string | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);

    const [search, setSearch] = useState("");
    const [decisionFilter, setDecisionFilter] = useState<FilterDecision>('ALL');
    const [sortField, setSortField] = useState<SortField>('finalScore');
    const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null);
    const canRunScanNow = can('scan.run_once');
    const runScanTooltip = disabledByPermissionTooltip('scan.run_once', canRunScanNow);

    const pinScanId = useCallback((id: string, replace = true) => {
        setResolvedScanId(id);
        navigate(`/scan/${id}`, { replace });
    }, [navigate]);

    const loadLatestScan = useCallback(async () => {
        if (routeScanRunId) {
            setResolvedScanId(routeScanRunId);
            setLoadError(null);
            return;
        }

        try {
            const latest = await getLatestScan();
            setLoadError(null);
            if (latest?.id) {
                pinScanId(latest.id, true);
            }
        } catch (error) {
            setLoadError(toUiErrorMessage(error, 'Failed to load live scan state'));
        }
    }, [pinScanId, routeScanRunId]);

    useEffect(() => {
        void loadLatestScan();
    }, [loadLatestScan]);

    const startScanPinned = useCallback(async () => {
        if (!canRunScanNow) {
            return;
        }

        setRunStarting(true);
        setRunError(null);
        const previousLatest = await getLatestScan().catch(() => null);
        try {
            const started = await runScanOnce();
            if (started.scanRunId) {
                pinScanId(started.scanRunId, false);
                return;
            }

            for (let i = 0; i < 20; i += 1) {
                await new Promise(resolve => setTimeout(resolve, 1000));
                const latest = await getLatestScan().catch(() => null);
                if (!latest?.id) continue;
                if (latest.id !== previousLatest?.id && latest.status === 'STARTED') {
                    pinScanId(latest.id, false);
                    return;
                }
            }

            setRunError("Scan started but run ID was not resolved yet. Please refresh.");
        } catch (err) {
            setRunError(toUiErrorMessage(err, 'Failed to start scan'));
        } finally {
            setRunStarting(false);
        }
    }, [canRunScanNow, pinScanId]);

    useEffect(() => {
        if (searchParams.get('run') !== '1' || autoRunTriggeredRef.current) {
            return;
        }
        autoRunTriggeredRef.current = true;
        void startScanPinned();
    }, [searchParams, startScanPinned]);

    const live = useLiveScan(resolvedScanId);
    const scanId = live.finalSummary?.id || live.summary?.id || resolvedScanId;
    const status = live.status;

    const sortedEvals = useMemo(() => {
        let arr = Array.from(live.evaluations.values());
        if (arr.length === 0 && live.finalEvals.length > 0) {
            arr = live.finalEvals;
        }

        if (search) {
            arr = arr.filter(e => e.symbol.includes(search));
        }
        if (decisionFilter !== 'ALL') {
            arr = arr.filter(e => e.decision === decisionFilter);
        }

        arr.sort((a, b) => {
            let valA: number = 0;
            let valB: number = 0;

            switch (sortField) {
                case 'quoteVolume':
                    valA = a.quoteVolumeUsdt ?? -999999;
                    valB = b.quoteVolumeUsdt ?? -999999;
                    break;
                case 'rank':
                    valA = a.rankInUniverse ?? 999999;
                    valB = b.rankInUniverse ?? 999999;
                    break;
                case 'finalScore':
                    valA = a.finalScore ?? -999999;
                    valB = b.finalScore ?? -999999;
                    break;
                case 'confidence':
                    valA = a.confidence ?? -999999;
                    valB = b.confidence ?? -999999;
                    break;
                case 'rrTp1':
                    valA = a.rrTp1 ?? -999999;
                    valB = b.rrTp1 ?? -999999;
                    break;
            }

            if (sortField === 'rank') {
                return valA - valB;
            }
            return valB - valA;
        });

        return arr;
    }, [decisionFilter, live.evaluations, live.finalEvals, search, sortField]);

    return (
        <div className="space-y-6 animate-in fade-in duration-500 pb-12">
            <div className="flex justify-end">
                <button
                    onClick={() => void startScanPinned()}
                    disabled={runStarting || !canRunScanNow}
                    title={runScanTooltip}
                    className="inline-flex items-center gap-2 px-4 py-2 rounded-lg border border-blue-600/60 bg-blue-700/20 hover:bg-blue-700/35 disabled:opacity-60 text-sm"
                >
                    <Play size={14} />
                    {runStarting ? 'Starting...' : 'Run Scan Now'}
                </button>
            </div>

            {runError && (
                <div className="text-xs text-rose-300 bg-rose-900/20 border border-rose-700/50 rounded px-3 py-2">
                    {runError}
                </div>
            )}
            {loadError && (
                <div className="flex items-center justify-between gap-3 text-xs text-amber-100 bg-amber-900/30 border border-amber-600/40 rounded px-3 py-2">
                    <span>{loadError}</span>
                    <button
                        onClick={() => void loadLatestScan()}
                        className="px-2 py-1 rounded border border-amber-500/50 hover:bg-amber-500/20"
                    >
                        Retry
                    </button>
                </div>
            )}

            <ScanHeaderCard
                summary={live.finalSummary || live.summary}
                failureReason={live.finalSummary?.notes || live.summary?.notes || null}
                sseConnected={live.sseConnected}
                reconnecting={live.reconnecting}
                polling={live.polling}
                statusOverride={status}
                lastUpdatedAt={live.lastUpdatedAt}
            />

            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                <div className="lg:col-span-2">
                    <PhaseTimeline
                        phases={live.phases}
                        scanStatus={status}
                        latestCandidateEvent={live.latestCandidateEvent}
                    />
                </div>
                <div>
                    <ProgressBarCard
                        evaluated={live.progress?.evaluated || 0}
                        total={live.progress?.total || 300}
                        validCount={live.progress?.validCount || 0}
                        noTradeCount={live.progress?.noTradeCount || 0}
                        eligibleCount={live.progress?.eligibleCount || 0}
                        dataErrorCount={live.progress?.dataErrorCount || 0}
                        latestCandidateEvent={live.latestCandidateEvent}
                    />
                </div>
            </div>

            <ChartsGrid charts={live.finalCharts} loading={status !== "FINISHED" && status !== "FAILED"} />

            <div className="pt-4">
                <h3 className="text-lg font-semibold text-white mb-2">Evaluations Pipeline</h3>
                <FilterBar
                    search={search} setSearch={setSearch}
                    decision={decisionFilter} setDecision={setDecisionFilter}
                    sort={sortField} setSort={setSortField}
                />

                <EvaluationsTable
                    rows={sortedEvals}
                    onRowClick={setSelectedSymbol}
                />
            </div>

            <CoinDetailDrawer
                scanRunId={scanId}
                symbol={selectedSymbol}
                onClose={() => setSelectedSymbol(null)}
            />
        </div>
    );
}
