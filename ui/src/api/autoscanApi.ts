import { apiClient } from './axiosSetup';

export interface AutoScanRunSnapshot {
    id: string;
    status: string;
    triggerType: string;
    requestedAt: string | null;
    startedAt: string | null;
    finishedAt: string | null;
    errorCode: string | null;
    errorMessage: string | null;
    correlationId: string | null;
}

export interface AutoScanStateResponse {
    autoscanEnabled: boolean;
    safeMode: boolean;
    intervalMinutes: number;
    nextRunAt: string | null;
    runningRun: AutoScanRunSnapshot | null;
    lastRun: AutoScanRunSnapshot | null;
    recentRuns: AutoScanRunSnapshot[];
    serverTime: string;
}

export async function getAutoScanState(): Promise<AutoScanStateResponse> {
    const response = await apiClient.get<AutoScanStateResponse>('/api/v1/scans/autoscan/state');
    return response.data;
}
