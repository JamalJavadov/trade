import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CheckCircle, ChevronDown, ChevronRight, Copy, Info, RefreshCw, TriangleAlert } from 'lucide-react';
import type {
    RecommendationDTO,
    RecommendationPlaceabilityDTO,
    RiskPreviewResponseDTO
} from '../api/client';
import { getRecommendationPlaceability, getSettings, previewRisk, runScanOnce } from '../api/client';
import { buildManualOrderGuide } from '../utils/buildManualOrderGuide';
import { calcNotional, formatPrice } from '../utils/format';
import { usePermissions } from '../hooks/usePermissions';
import { disabledByPermissionTooltip } from '../utils/permissionUi';

interface Props {
    rec: RecommendationDTO;
    onLockStateChange?: (locked: boolean, reason: string) => void;
}

function CopyButton({
    value,
    disabled,
    disabledTitle
}: {
    value: string;
    disabled?: boolean;
    disabledTitle?: string;
}) {
    const [copied, setCopied] = useState(false);
    const handleCopy = useCallback(() => {
        if (disabled) return;
        void navigator.clipboard.writeText(value);
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
    }, [disabled, value]);
    return (
        <button
            onClick={handleCopy}
            title={disabled ? (disabledTitle || 'Copy is disabled') : 'Copy'}
            disabled={disabled}
            className={`ml-2 transition-colors shrink-0 ${disabled
                ? 'text-gray-600 cursor-not-allowed'
                : 'text-gray-500 hover:text-gray-300'
                }`}
        >
            {copied ? <CheckCircle size={14} className="text-green-400" /> : <Copy size={14} />}
        </button>
    );
}

interface FieldRowProps {
    label: string;
    value: string;
    highlight?: boolean;
    copyDisabled?: boolean;
    copyDisabledTitle?: string;
}

function FieldRow({ label, value, highlight, copyDisabled, copyDisabledTitle }: FieldRowProps) {
    return (
        <div className="flex items-center justify-between py-2 border-b border-gray-700/50 last:border-0">
            <span className="text-xs text-gray-500 uppercase tracking-widest w-44 shrink-0">{label}</span>
            <div className="flex items-center flex-1 justify-end">
                <span className={`font-mono text-sm ${highlight ? 'text-yellow-300 font-bold' : 'text-white'}`}>
                    {value}
                </span>
                <CopyButton value={value} disabled={copyDisabled} disabledTitle={copyDisabledTitle} />
            </div>
        </div>
    );
}

function formatRr(value: number | string | null | undefined): string {
    if (value == null) return '—';
    const asNumber = Number(value);
    if (Number.isNaN(asNumber)) return '—';
    return `${asNumber.toFixed(2)}x`;
}

export const BinanceFillGuide: React.FC<Props> = ({ rec, onLockStateChange }) => {
    const { can } = usePermissions();
    const guide = buildManualOrderGuide(rec);
    const navigate = useNavigate();
    const [advancedOpen, setAdvancedOpen] = useState(false);
    const [riskPreview, setRiskPreview] = useState<RiskPreviewResponseDTO | null>(null);
    const [riskWarning, setRiskWarning] = useState<string | null>(null);
    const [placeability, setPlaceability] = useState<RecommendationPlaceabilityDTO | null>(null);
    const [placeabilityLoading, setPlaceabilityLoading] = useState(false);
    const [placeabilityError, setPlaceabilityError] = useState(false);
    const [rescanning, setRescanning] = useState(false);
    const placeabilityInFlightRef = useRef(false);
    const canRunScanNow = can('scan.run_once');
    const runScanTooltip = disabledByPermissionTooltip('scan.run_once', canRunScanNow);

    const loadPlaceability = useCallback(async () => {
        if (placeabilityInFlightRef.current) return;
        placeabilityInFlightRef.current = true;
        setPlaceabilityLoading(true);
        setPlaceabilityError(false);
        try {
            const res = await getRecommendationPlaceability(rec.id);
            setPlaceability(res);
        } catch {
            setPlaceabilityError(true);
            setPlaceability(null);
        } finally {
            setPlaceabilityLoading(false);
            placeabilityInFlightRef.current = false;
        }
    }, [rec.id]);

    useEffect(() => {
        if (typeof document === 'undefined') {
            void loadPlaceability();
            return;
        }

        let intervalId: number | null = null;

        const startPolling = () => {
            if (intervalId != null) return;
            intervalId = window.setInterval(() => {
                if (document.visibilityState === 'visible') {
                    void loadPlaceability();
                }
            }, 3000);
        };

        const stopPolling = () => {
            if (intervalId != null) {
                window.clearInterval(intervalId);
                intervalId = null;
            }
        };

        const handleVisibilityChange = () => {
            if (document.visibilityState === 'visible') {
                void loadPlaceability();
                startPolling();
            } else {
                stopPolling();
            }
        };

        handleVisibilityChange();
        document.addEventListener('visibilitychange', handleVisibilityChange);

        return () => {
            stopPolling();
            document.removeEventListener('visibilitychange', handleVisibilityChange);
        };
    }, [loadPlaceability]);

    useEffect(() => {
        const fetchRisk = async () => {
            try {
                const settings = await getSettings();
                const preview = await previewRisk({
                    budgetUsdt: settings.budgetUsdt,
                    maxBudgetPct: settings.maxBudgetPct,
                    equityOverrideUsdt: settings.equityOverrideUsdt,
                    maxEquityPct: settings.maxEquityPct,
                });
                setRiskPreview(preview);

                const slP = parseFloat(String(rec.slOrder?.stopPrice ?? '0'));
                const entryP = parseFloat(String(rec.entryOrder?.price ?? '0'));
                const qty = parseFloat(String(rec.entryOrder?.quantity ?? '0'));
                if (slP > 0 && entryP > 0 && qty > 0 && preview.effectiveRiskUsdt != null) {
                    const estimatedLoss = Math.abs(entryP - slP) * qty;
                    if (estimatedLoss > preview.effectiveRiskUsdt * 1.1) {
                        setRiskWarning(
                            `Estimated SL loss (~${estimatedLoss.toFixed(2)} USDT) exceeds effective risk budget (${preview.effectiveRiskUsdt.toFixed(2)} USDT). Review before trading.`
                        );
                    }
                }
            } catch {
                // non-critical
            }
        };
        void fetchRisk();
    }, [rec]);

    const isLong = rec.side === 'BUY';
    const tickText = placeability?.tickSize != null ? String(placeability.tickSize) : undefined;
    const tpDisplay = placeability?.tpDisplay ?? rec.tpOrder?.stopPrice;
    const slDisplay = placeability?.slDisplay ?? rec.slOrder?.stopPrice;
    const tpDisplayText = formatPrice(tpDisplay, tickText);
    const slDisplayText = formatPrice(slDisplay, tickText);

    const manualPlacementAllowed = !!placeability && placeability.manualPlacementAllowed && !placeabilityError;
    const hardStop = !manualPlacementAllowed;

    const copyLockReason = useMemo(() => {
        if (placeabilityError) {
            return 'Live MARK preflight unavailable. Refresh mark or rescan before placing.';
        }
        if (!placeability) {
            return placeabilityLoading
                ? 'Live MARK preflight pending. Wait for validation or refresh mark.'
                : 'Live MARK preflight pending. Refresh mark before placing.';
        }
        if (!placeability.manualPlacementAllowed) {
            return 'Trade is not placeable now. Refresh mark or rescan first.';
        }
        return '';
    }, [placeabilityError, placeability, placeabilityLoading]);

    useEffect(() => {
        onLockStateChange?.(hardStop, hardStop ? copyLockReason : '');
    }, [hardStop, copyLockReason, onLockStateChange]);

    const quickSummary = useMemo(() => {
        const side = isLong ? 'LONG' : 'SHORT';
        const markTxt = placeability ? ` — MARK: ${formatPrice(placeability.markPrice, tickText)}` : '';
        return `${rec.symbol} — ${side} — ${guide.marginMode} — ${guide.leverage} — Size: ${guide.quantityCoin} ${guide.quantitySymbol} — SL: ${slDisplayText} — TP: ${tpDisplayText} (MARK)${markTxt}`;
    }, [
        rec.symbol,
        isLong,
        placeability,
        tickText,
        guide.marginMode,
        guide.leverage,
        guide.quantityCoin,
        guide.quantitySymbol,
        slDisplayText,
        tpDisplayText,
    ]);

    const entryPrice = rec.entryOrder?.price;
    const approxLoss = calcNotional(
        rec.entryOrder?.quantity,
        entryPrice != null && slDisplay != null
            ? Math.abs((Number(entryPrice)) - (Number(slDisplay)))
            : null
    );

    const handleRescanNow = useCallback(async () => {
        if (!canRunScanNow) {
            return;
        }

        setRescanning(true);
        try {
            const run = await runScanOnce();
            if (run.scanRunId) {
                navigate(`/scan/${run.scanRunId}`);
            } else {
                navigate('/scan?run=1');
            }
        } finally {
            setRescanning(false);
        }
    }, [canRunScanNow, navigate]);

    const rowCopyProps = {
        copyDisabled: hardStop,
        copyDisabledTitle: copyLockReason,
    };

    return (
        <div className="bg-gray-800 border border-blue-700/40 rounded-xl p-6 shadow-xl mt-6">
            <div className="flex items-center gap-2 mb-4">
                <Info size={16} className="text-blue-400 shrink-0" />
                <h2 className="text-lg font-bold text-blue-300 tracking-wide">Binance Manual Fill Guide</h2>
            </div>

            <div className={`rounded-lg px-4 py-3 mb-5 text-sm font-mono font-semibold
                ${isLong ? 'bg-green-900/30 border border-green-700/50 text-green-300' : 'bg-red-900/30 border border-red-700/50 text-red-300'}`}>
                {quickSummary}
                <CopyButton value={quickSummary} disabled={hardStop} disabledTitle={copyLockReason} />
            </div>

            {manualPlacementAllowed ? (
                <div className="rounded-lg px-4 py-3 mb-5 border bg-green-900/20 border-green-700/40 text-green-300">
                    <div className="text-sm">
                        <p className="font-bold mb-1">Preflight VALID</p>
                        <p className="text-xs text-green-100/80">
                            Rule: {placeability?.ruleText} | Required: {placeability?.requiredInequality}
                        </p>
                        <p className="text-xs text-green-100/80 mt-1">
                            MARK: {formatPrice(placeability?.markPrice, tickText)} | Tick: {formatPrice(placeability?.tickSize)} | RR (live): {formatRr(placeability?.liveRrToTp1)} / Min {formatRr(placeability?.minRrRequired)}
                        </p>
                        <button
                            onClick={() => void loadPlaceability()}
                            disabled={placeabilityLoading}
                            className="mt-2 inline-flex items-center gap-2 px-3 py-1.5 text-xs rounded border border-green-600/50 bg-green-700/20 hover:bg-green-700/30 disabled:opacity-60"
                        >
                            <RefreshCw size={13} />
                            Refresh Mark
                        </button>
                    </div>
                </div>
            ) : (
                <div className="rounded-lg px-4 py-3 mb-5 border bg-red-900/30 border-red-700/60 text-red-300">
                    <div className="flex items-start gap-3">
                        <TriangleAlert size={18} className="shrink-0 mt-0.5" />
                        <div className="w-full">
                            <p className="font-bold text-sm mb-1">Manual placement is LOCKED (NOT PLACEABLE).</p>
                            {placeabilityError ? (
                                <p className="text-xs text-red-100/80 mb-2">
                                    Live MARK preflight could not be fetched. Placement is blocked until validation succeeds.
                                </p>
                            ) : (
                                <p className="text-xs text-red-100/80 mb-2">
                                    {placeabilityLoading && !placeability
                                        ? 'Fetching live MARK preflight...'
                                        : 'This setup violates Binance entry-panel TP/SL direction rules or RR guard. Do not place manually until valid.'}
                                </p>
                            )}

                            {!!placeability && (
                                <>
                                    <p className="text-xs text-red-100/80 mb-2">
                                        Reason: {placeability.reasonCode} — {placeability.reasonText}
                                    </p>
                                    <p className="text-xs text-red-100/70 mb-2">
                                        Rule: {placeability.ruleText} | Required: {placeability.requiredInequality}
                                    </p>
                                    <p className="text-xs text-red-100/70 mb-2">
                                        MARK: {formatPrice(placeability.markPrice, tickText)} | Tick: {formatPrice(placeability.tickSize)} | RR (live): {formatRr(placeability.liveRrToTp1)} / Min {formatRr(placeability.minRrRequired)}
                                    </p>
                                    {placeability.violations.length > 0 && (
                                        <ul className="text-xs text-red-100/80 mb-3 space-y-1">
                                            {placeability.violations.map((v, idx) => (
                                                <li key={idx}>• {v}</li>
                                            ))}
                                        </ul>
                                    )}
                                </>
                            )}

                            <div className="flex flex-wrap gap-2">
                                <button
                                    onClick={() => void loadPlaceability()}
                                    disabled={placeabilityLoading}
                                    className="inline-flex items-center gap-2 px-3 py-1.5 text-xs rounded border border-red-600/60 bg-red-700/20 hover:bg-red-700/40 disabled:opacity-60"
                                >
                                    <RefreshCw size={13} />
                                    Refresh Mark
                                </button>
                                <button
                                    onClick={() => void handleRescanNow()}
                                    disabled={rescanning || !canRunScanNow}
                                    title={runScanTooltip}
                                    className="px-3 py-1.5 text-xs rounded border border-blue-600/60 bg-blue-700/20 hover:bg-blue-700/40 disabled:opacity-60"
                                >
                                    {rescanning ? 'Rescanning...' : 'Rescan now'}
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            <div className={hardStop ? 'opacity-40 blur-[1px] pointer-events-none select-none' : ''}>
                <div className="bg-gray-900/60 border border-gray-700 rounded-lg px-4 py-2 mb-5">
                    <p className="text-xs text-gray-500 uppercase tracking-widest mb-2 pt-2">Fill These Fields in Binance</p>
                    <FieldRow label="Margin Mode" value={guide.marginMode} {...rowCopyProps} />
                    <FieldRow label="Leverage" value={guide.leverage} highlight {...rowCopyProps} />
                    <FieldRow label="Side" value={guide.side} highlight {...rowCopyProps} />
                    <FieldRow label="Order Tab" value={guide.orderTab} {...rowCopyProps} />
                    <FieldRow label="Action Button" value={guide.actionButton} highlight {...rowCopyProps} />
                    <FieldRow
                        label={`Size (${guide.quantitySymbol})`}
                        value={`${guide.quantityCoin} ${guide.quantitySymbol}`}
                        highlight
                        {...rowCopyProps}
                    />
                    {guide.notionalUsdt && (
                        <FieldRow label="Size (Est. USDT)" value={`~${guide.notionalUsdt} USDT`} {...rowCopyProps} />
                    )}
                    <FieldRow label="TP / SL Toggle" value="ON" {...rowCopyProps} />
                    <FieldRow label="Take Profit Trigger" value="MARK" {...rowCopyProps} />
                    <FieldRow
                        label="Take Profit Price"
                        value={tpDisplayText}
                        highlight
                        {...rowCopyProps}
                    />
                    <FieldRow label="Stop Loss Trigger" value="MARK" {...rowCopyProps} />
                    <FieldRow
                        label="Stop Loss Price"
                        value={slDisplayText}
                        highlight
                        {...rowCopyProps}
                    />
                    <FieldRow label="Reduce-Only (entry)" value="OFF" {...rowCopyProps} />
                </div>

                {guide.hasAdvanced && (
                    <div className="mb-5">
                        <button
                            onClick={() => setAdvancedOpen(o => !o)}
                            className="flex items-center gap-2 text-sm text-gray-400 hover:text-white transition-colors"
                        >
                            {advancedOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                            Advanced (Separate Exit Orders / TP2 / TP3)
                        </button>
                        {advancedOpen && (
                            <div className="mt-3 bg-gray-900/60 border border-gray-700 rounded-lg px-4 py-3 text-sm text-gray-300">
                                <p className="text-xs text-yellow-300 mb-3">
                                    Advanced mode only. Default safe path is the same entry-panel TP/SL toggle flow above.
                                </p>
                                <ul className="space-y-2">
                                    {guide.separateExitGuide.map((item, i) => (
                                        <li key={i} className="flex items-start gap-2">
                                            <CheckCircle size={14} className="text-blue-400 shrink-0 mt-0.5" />
                                            {item}
                                        </li>
                                    ))}
                                </ul>
                                <p className="mt-3 text-xs text-gray-400">Advanced TP2/TP3 partial scaling is not available in the current API response.</p>
                            </div>
                        )}
                    </div>
                )}

                <div className="bg-gray-900/60 border border-gray-700 rounded-lg px-4 py-3 mb-5">
                    <p className="text-xs text-gray-500 uppercase tracking-widest mb-3">Safety Checklist</p>
                    <ul className="space-y-2">
                        {guide.checklist.map((item, i) => (
                            <li key={i} className="flex items-start gap-2 text-sm text-gray-300">
                                <CheckCircle size={14} className="text-green-500 shrink-0 mt-0.5" />
                                {item}
                            </li>
                        ))}
                    </ul>
                </div>
            </div>

            {hardStop && (
                <p className="text-xs text-red-300 mb-5">
                    Fill guide is locked until live MARK preflight is valid. Use only Refresh Mark or Rescan now.
                </p>
            )}

            {riskPreview && (
                <div className="bg-gray-900/60 border border-gray-700 rounded-lg px-4 py-3">
                    <p className="text-xs text-gray-500 uppercase tracking-widest mb-3">Risk Budget Consistency</p>
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-center text-sm">
                        {riskPreview.effectiveRiskUsdt != null && (
                            <div>
                                <p className="text-xs text-gray-500 mb-1">Effective Risk</p>
                                <p className="font-mono text-yellow-300">{riskPreview.effectiveRiskUsdt.toFixed(2)} USDT</p>
                            </div>
                        )}
                        {riskPreview.riskUsdtFromBudget != null && (
                            <div>
                                <p className="text-xs text-gray-500 mb-1">From Budget %</p>
                                <p className="font-mono text-white">{riskPreview.riskUsdtFromBudget.toFixed(2)} USDT</p>
                            </div>
                        )}
                        {approxLoss && (
                            <div>
                                <p className="text-xs text-gray-500 mb-1">Est. SL Loss</p>
                                <p className="font-mono text-white">~{approxLoss} USDT</p>
                            </div>
                        )}
                        {rec.entryOrder?.price && (
                            <div>
                                <p className="text-xs text-gray-500 mb-1">Entry Ref</p>
                                <p className="font-mono text-white">{formatPrice(rec.entryOrder.price)}</p>
                            </div>
                        )}
                    </div>
                    {riskWarning && (
                        <div className="mt-3 flex items-start gap-2 bg-yellow-900/30 border border-yellow-700/50 rounded px-3 py-2 text-sm text-yellow-300">
                            <TriangleAlert size={14} className="shrink-0 mt-0.5" />
                            {riskWarning}
                        </div>
                    )}
                </div>
            )}

            <p className="text-xs text-gray-600 mt-4 text-center">
                This guide covers manual review and manual entry guidance. Real Binance execution is only available from
                the dedicated confirmation button on the recommendation detail page.
            </p>
        </div>
    );
};
