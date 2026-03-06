import { create } from 'zustand';

export type ToastKind = 'success' | 'error';

export interface ToastItem {
    id: string;
    kind: ToastKind;
    message: string;
}

interface ToastStoreState {
    toasts: ToastItem[];
    pushToast: (message: string, kind?: ToastKind, durationMs?: number) => string;
    removeToast: (id: string) => void;
}

const DEFAULT_DURATION_MS = 3000;

export const useToastStore = create<ToastStoreState>((set) => ({
    toasts: [],
    pushToast: (message, kind = 'success', durationMs = DEFAULT_DURATION_MS) => {
        const id = Math.random().toString(36).slice(2, 11);
        set((state) => ({
            toasts: [...state.toasts, { id, kind, message }],
        }));

        window.setTimeout(() => {
            set((state) => ({
                toasts: state.toasts.filter((item) => item.id !== id),
            }));
        }, durationMs);

        return id;
    },
    removeToast: (id) => {
        set((state) => ({
            toasts: state.toasts.filter((item) => item.id !== id),
        }));
    },
}));
