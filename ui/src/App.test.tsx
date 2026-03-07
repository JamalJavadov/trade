import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';

vi.mock('./hooks/usePermissions', () => ({
    PermissionsProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('./pages/DashboardPage', () => ({ DashboardPage: () => <div>dashboard-page</div> }));
vi.mock('./pages/RecommendationDetailPage', () => ({ RecommendationDetailPage: () => <div>recommendation-page</div> }));
vi.mock('./pages/JournalPage', () => ({ JournalPage: () => <div>journal-page</div> }));
vi.mock('./pages/AiPage', () => ({ AiPage: () => <div>ai-page</div> }));
vi.mock('./pages/ScanPage', () => ({ ScanPage: () => <div>scan-page</div> }));
vi.mock('./pages/ScanHistoryPage', () => ({ ScanHistoryPage: () => <div>scan-history-page</div> }));
vi.mock('./pages/ScanReplayPage', () => ({ ScanReplayPage: () => <div>scan-replay-page</div> }));
vi.mock('./pages/ErrorCenterPage', () => ({ ErrorCenterPage: () => <div>error-center-page</div> }));
vi.mock('./pages/DemoTradingPage', () => ({ DemoTradingPage: () => <div>demo-page</div> }));
vi.mock('./pages/PermissionsPage', () => ({ PermissionsPage: () => <div>control-center-page</div> }));
vi.mock('./components/error/ErrorBanner', () => ({ ErrorBanner: () => null }));
vi.mock('./components/alerts/AlertEngine', () => ({ AlertEngine: () => null }));
vi.mock('./components/alerts/PlaceableAlertModal', () => ({ PlaceableAlertModal: () => null }));
vi.mock('./components/ToastHost', () => ({ ToastHost: () => null }));
vi.mock('./components/scan/LiveScanErrorBoundary', () => ({ LiveScanErrorBoundary: ({ children }: { children: React.ReactNode }) => <>{children}</> }));

import App from './App';

describe('App routes', () => {
    beforeEach(() => {
        window.history.replaceState({}, '', '/');
    });

    afterEach(() => {
        cleanup();
    });

    it('redirects / to /dashboard without a loop', async () => {
        window.history.replaceState({}, '', '/');
        render(<App />);

        expect(await screen.findByText('dashboard-page')).toBeInTheDocument();
    });

    it('redirects /permissions to the control center route', async () => {
        window.history.replaceState({}, '', '/permissions');
        render(<App />);

        expect(await screen.findByText('control-center-page')).toBeInTheDocument();
    });

    it('redirects unknown routes to /dashboard', async () => {
        window.history.replaceState({}, '', '/not-a-route');
        render(<App />);

        expect(await screen.findByText('dashboard-page')).toBeInTheDocument();
    });
});
