import { BrowserRouter as Router, Routes, Route, Navigate, Link } from 'react-router-dom';
import { DashboardPage } from './pages/DashboardPage';
import { RecommendationDetailPage } from './pages/RecommendationDetailPage';
import { JournalPage } from './pages/JournalPage';
import { AiPage } from './pages/AiPage';
import { ScanPage } from './pages/ScanPage';
import { ScanHistoryPage } from './pages/ScanHistoryPage';
import { ScanReplayPage } from './pages/ScanReplayPage';
import { ErrorCenterPage } from './pages/ErrorCenterPage';
import { DemoTradingPage } from './pages/DemoTradingPage';
import { PermissionsPage } from './pages/PermissionsPage';
import { ErrorBanner } from './components/error/ErrorBanner';
import { LiveScanErrorBoundary } from './components/scan/LiveScanErrorBoundary';
import { AlertEngine } from './components/alerts/AlertEngine';
import { PlaceableAlertModal } from './components/alerts/PlaceableAlertModal';
import { ToastHost } from './components/ToastHost';
import { PermissionsProvider } from './hooks/usePermissions';

function LiveScanRoute() {
  return (
    <LiveScanErrorBoundary>
      <ScanPage />
    </LiveScanErrorBoundary>
  );
}

function App() {
  return (
    <PermissionsProvider>
      <Router>
        <div className="min-h-screen bg-gray-900 text-gray-100 font-sans antialiased">
          <header className="bg-gray-800 border-b border-gray-700 shadow-sm sticky top-0 z-10">
            <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
              <div className="flex items-center space-x-3">
                <div className="w-8 h-8 rounded bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center font-bold text-white shadow-lg">
                  T
                </div>
                <Link to="/" className="text-xl font-bold tracking-tight text-white hover:text-blue-200 transition-colors">TradeBot</Link>
                <span className="rounded-full border border-blue-700/60 bg-blue-700/20 px-2 py-1 text-[10px] font-bold uppercase tracking-wider text-blue-200">
                  Paper Trading / Learning Mode
                </span>
              </div>
              <nav className="flex flex-wrap justify-end gap-4">
                <Link to="/dashboard" className="text-gray-300 hover:text-white font-medium">Dashboard</Link>
                <Link to="/scan" className="text-gray-300 hover:text-blue-400 font-medium">Live Scan</Link>
                <Link to="/scan/history" className="text-gray-300 hover:text-blue-200 font-medium">Scan History</Link>
                <Link to="/journal" className="text-gray-300 hover:text-white font-medium">Journal</Link>
                <Link to="/demo" className="text-blue-300 hover:text-blue-200 font-semibold">Demo Trading</Link>
                <Link to="/ai" className="text-purple-400 hover:text-purple-300 font-bold flex items-center">
                  <span className="mr-1">AI</span>
                </Link>
                <Link to="/control-center" className="text-teal-300 hover:text-teal-200 font-semibold">Control Center</Link>
                <Link to="/errors" className="text-rose-400 hover:text-rose-300 font-bold flex items-center ml-2 border-l border-gray-700 pl-6">
                  Errors
                </Link>
              </nav>
            </div>
          </header>

          <ErrorBanner />
          <AlertEngine />
          <ToastHost />

          <main className="pb-12 max-w-6xl mx-auto pt-8 px-6">
            <Routes>
              <Route path="/dashboard" element={<DashboardPage />} />
              <Route path="/scan" element={<LiveScanRoute />} />
              <Route path="/scan/:scanRunId" element={<LiveScanRoute />} />
              <Route path="/scan/history" element={<ScanHistoryPage />} />
              <Route path="/replay/:scanRunId" element={<ScanReplayPage />} />
              <Route path="/journal" element={<JournalPage />} />
              <Route path="/demo" element={<DemoTradingPage />} />
              <Route path="/demo/trades/:id" element={<DemoTradingPage />} />
              <Route path="/ai" element={<AiPage />} />
              <Route path="/control-center" element={<PermissionsPage />} />
              <Route path="/permissions" element={<Navigate to="/control-center" replace />} />
              <Route path="/errors" element={<ErrorCenterPage />} />
              <Route path="/recommendation/:id" element={<RecommendationDetailPage />} />
              <Route path="/recommendation" element={<Navigate to="/dashboard" replace />} />
              <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Routes>
          </main>
          <PlaceableAlertModal />
        </div>
      </Router>
    </PermissionsProvider>
  );
}

export default App;
