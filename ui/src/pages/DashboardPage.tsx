import React, { useState } from 'react';
import { StatusCard } from '../components/StatusCard';
import { ScanButton } from '../components/ScanButton';
import { RecommendationCard } from '../components/RecommendationCard';
import { OperatorPanel } from '../components/OperatorPanel';
import { BudgetTargetAutoExecutionCard } from '../components/dashboard/BudgetTargetAutoExecutionCard';
import { RiskSettingsCard } from '../components/dashboard/RiskSettingsCard';
import { AiModelSettingsCard } from '../components/settings/AiModelSettingsCard';
import { Banner } from '../components/Banner';
import { PlaceableAlertBanner } from '../components/alerts/PlaceableAlertBanner';
import { getStatus, getLatestRecommendation, getLiveAiModels, updateLiveAiModels, testLiveAiModels } from '../api/client';
import { usePolling } from '../hooks/usePolling';
import { useControlCenter } from '../hooks/usePermissions';
import { listLiveExecutions } from '../api/liveTradingApi';
import { journalStore } from '../store/journalStore';
import { Link } from 'react-router-dom';

export const DashboardPage: React.FC = () => {
    const [isScanning, setIsScanning] = useState(false);
    const { state: controlCenterState } = useControlCenter();

    // Poll status every 10s
    const {
        data: status,
        error: statusError,
        loading: statusLoading,
        refetch: refetchStatus
    } = usePolling(getStatus, 10000, isScanning);

    // Poll recommendation every 10s
    const {
        data: recommendation,
        error: recError,
        loading: recLoading,
        refetch: refetchRec
    } = usePolling(getLatestRecommendation, 10000, isScanning);

    const error = statusError || recError;

    React.useEffect(() => {
        if (recommendation) {
            journalStore.upsertFromLatest(recommendation);
        }
    }, [recommendation]);

    const liveExecutionStatus = React.useMemo(() => {
        const permissions = controlCenterState?.config.permissions ?? {};
        return permissions['live.execution.enabled'] === false ? 'disabled' : 'enabled';
    }, [controlCenterState]);

    const {
        data: latestExecution,
    } = usePolling(
        async () => {
            if (!recommendation?.id) {
                return null;
            }
            const executions = await listLiveExecutions({ recommendationId: recommendation.id, limit: 1 });
            return executions[0] ?? null;
        },
        10000,
        isScanning || !recommendation,
    );

    const handleScanStart = () => {
        setIsScanning(true);
    };

    const handleScanComplete = () => {
        setIsScanning(false);
        refetchStatus();
        refetchRec();
    };

    return (
        <div className="max-w-4xl mx-auto p-6">
            <div className="mb-8">
                <h1 className="text-3xl font-bold bg-gradient-to-r from-blue-400 to-indigo-400 bg-clip-text text-transparent">
                    TradeBot Dashboard
                </h1>
                <p className="text-gray-400 mt-2">Local Smart Money Fibonacci Auto-Scanner</p>
            </div>

            {error && (
                <Banner
                    message={error.message}
                    onRetry={() => {
                        refetchStatus();
                        refetchRec();
                    }}
                />
            )}
            <PlaceableAlertBanner />

            <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                <div className="md:col-span-2 space-y-6">
                    <StatusCard status={status} loading={statusLoading} />
                    <RiskSettingsCard />
                    <BudgetTargetAutoExecutionCard />
                    <AiModelSettingsCard
                        title="AI Models (LIVE)"
                        subtitle="Allowlist-only routing for live suggestion batching."
                        modeLabel="LIVE"
                        fetchSettings={getLiveAiModels}
                        updateSettings={updateLiveAiModels}
                        testCall={testLiveAiModels}
                    />
                </div>
                <div className="flex items-center justify-center bg-gray-800 rounded-lg border border-gray-700 p-6">
                    <ScanButton
                        onScanStart={handleScanStart}
                        onScanComplete={handleScanComplete}
                    />
                </div>
            </div>

            <OperatorPanel />

            <div className="mt-8">
                <div className="flex justify-between items-center mb-4 border-b border-gray-700 pb-2">
                    <div>
                        <h2 className="text-xl font-semibold text-gray-200">
                            Latest Setup
                        </h2>
                        <div className="mt-2 flex flex-wrap gap-2">
                            <span className="rounded-full border border-amber-700/40 bg-amber-900/20 px-3 py-1 text-[11px] font-semibold uppercase tracking-wider text-amber-200">
                                Live Execution {liveExecutionStatus}
                            </span>
                            {latestExecution && (
                                <span className="rounded-full border border-sky-700/40 bg-sky-900/20 px-3 py-1 text-[11px] font-semibold uppercase tracking-wider text-sky-200">
                                    Latest Execution {latestExecution.executionState}
                                </span>
                            )}
                        </div>
                    </div>
                    <Link to="/journal" className="text-sm text-blue-400 hover:text-blue-300">
                        View Journal History &rarr;
                    </Link>
                </div>
                <RecommendationCard
                    recommendation={recommendation}
                    loading={recLoading}
                    liveExecutionStatus={liveExecutionStatus}
                    latestExecutionState={latestExecution?.executionState ?? null}
                />
            </div>
        </div>
    );
};
