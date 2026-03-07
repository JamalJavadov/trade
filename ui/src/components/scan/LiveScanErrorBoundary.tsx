import React from 'react';
import { AlertTriangle, Copy, RefreshCw } from 'lucide-react';
import { useErrorStore } from '../../store/errorStore';

interface BoundaryProps extends React.PropsWithChildren {
    onReset: () => void;
}

interface BoundaryState {
    hasError: boolean;
    error?: Error;
    componentStack?: string;
}

class LiveScanErrorBoundaryInner extends React.Component<BoundaryProps, BoundaryState> {
    state: BoundaryState = {
        hasError: false,
    };

    static getDerivedStateFromError(error: Error): BoundaryState {
        return {
            hasError: true,
            error,
        };
    }

    componentDidCatch(error: Error, info: React.ErrorInfo): void {
        const context = useErrorStore.getState().liveScanContext;
        useErrorStore.getState().addError({
            timestamp: new Date().toISOString(),
            path: window.location.pathname,
            errorCode: 'UI_RUNTIME_ERROR',
            message: 'Live Scan crashed',
            details: {
                errorMessage: error.message,
                stack: error.stack,
                componentStack: info.componentStack,
                scanRunId: context?.scanRunId ?? null,
                latestStatusPayload: context?.statusPayload ?? null,
                contextUpdatedAt: context?.updatedAt ?? null,
            },
            traceId: `ui-${Date.now()}`
        });
        this.setState({ componentStack: info.componentStack ?? undefined });
    }

    private copyCrashReport = () => {
        const context = useErrorStore.getState().liveScanContext;
        const report = {
            timestamp: new Date().toISOString(),
            page: window.location.pathname,
            errorMessage: this.state.error?.message,
            stack: this.state.error?.stack,
            componentStack: this.state.componentStack,
            scanRunId: context?.scanRunId ?? null,
            latestStatusPayload: context?.statusPayload ?? null,
            contextUpdatedAt: context?.updatedAt ?? null,
        };
        void navigator.clipboard.writeText(JSON.stringify(report, null, 2));
    };

    private reset = () => {
        this.setState({
            hasError: false,
            error: undefined,
            componentStack: undefined,
        });
        this.props.onReset();
    };

    render() {
        if (!this.state.hasError) {
            return this.props.children;
        }

        const context = useErrorStore.getState().liveScanContext;

        return (
            <div className="bg-rose-900/20 border border-rose-700/50 rounded-xl p-6">
                <div className="flex items-start gap-3">
                    <AlertTriangle className="text-rose-400 shrink-0 mt-0.5" size={20} />
                    <div className="flex-1">
                        <h2 className="text-lg font-bold text-rose-300 mb-1">Live Scan crashed</h2>
                        <p className="text-sm text-rose-100/80">
                            A runtime error interrupted rendering. Reset the scan view to remount the page without forcing a browser refresh.
                        </p>
                        <p className="text-xs text-rose-100/70 mt-2 font-mono">
                            ScanRunId: {context?.scanRunId ?? 'unknown'}
                        </p>
                        <div className="mt-4 flex flex-wrap gap-2">
                            <button
                                onClick={this.reset}
                                className="inline-flex items-center gap-2 px-3 py-1.5 text-xs rounded border border-rose-600/70 bg-rose-700/20 hover:bg-rose-700/40"
                            >
                                <RefreshCw size={13} />
                                Reset scan view
                            </button>
                            <button
                                onClick={this.copyCrashReport}
                                className="inline-flex items-center gap-2 px-3 py-1.5 text-xs rounded border border-slate-600 bg-slate-700/40 hover:bg-slate-700/70 text-slate-100"
                            >
                                <Copy size={13} />
                                Copy crash report
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        );
    }
}

export const LiveScanErrorBoundary: React.FC<React.PropsWithChildren> = ({ children }) => {
    const [resetKey, setResetKey] = React.useState(0);

    return (
        <LiveScanErrorBoundaryInner
            key={resetKey}
            onReset={() => setResetKey((current) => current + 1)}
        >
            {children}
        </LiveScanErrorBoundaryInner>
    );
};
