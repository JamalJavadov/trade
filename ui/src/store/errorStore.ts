import { create } from 'zustand';

export interface ApiErrorResponse {
    timestamp: string;
    path: string;
    errorCode: string;
    message: string;
    details?: any;
    traceId: string | null;
}

export interface ErrorRecord extends ApiErrorResponse {
    id: string;   // Local unique ID for the store
    resolved: boolean;
}

export interface LiveScanContextSnapshot {
    scanRunId: string | null;
    statusPayload: Record<string, unknown> | null;
    updatedAt: string;
}

interface ErrorStoreState {
    errors: ErrorRecord[];
    liveScanContext: LiveScanContextSnapshot | null;
    addError: (err: Omit<ErrorRecord, 'id' | 'resolved'>) => void;
    resolveError: (id: string) => void;
    clearErrors: () => void;
    setLiveScanContext: (snapshot: LiveScanContextSnapshot | null) => void;
}

const MAX_ERRORS = 50;

export const useErrorStore = create<ErrorStoreState>((set) => ({
    errors: [],
    liveScanContext: null,
    addError: (err) => set((state) => {
        const newError: ErrorRecord = {
            ...err,
            id: Math.random().toString(36).substring(2, 11),
            resolved: false
        };
        const updated = [newError, ...state.errors].slice(0, MAX_ERRORS);
        return { errors: updated };
    }),
    resolveError: (id) => set((state) => ({
        errors: state.errors.map(e => e.id === id ? { ...e, resolved: true } : e)
    })),
    clearErrors: () => set({ errors: [] }),
    setLiveScanContext: (snapshot) => set({ liveScanContext: snapshot })
}));
