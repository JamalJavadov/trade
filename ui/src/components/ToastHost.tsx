import { X } from 'lucide-react';
import { useToastStore } from '../store/toastStore';

export function ToastHost() {
    const toasts = useToastStore((state) => state.toasts);
    const removeToast = useToastStore((state) => state.removeToast);

    return (
        <div className="pointer-events-none fixed right-4 top-4 z-50 flex w-[360px] max-w-[calc(100vw-2rem)] flex-col gap-2">
            {toasts.map((toast) => (
                <div
                    key={toast.id}
                    className={`pointer-events-auto rounded border px-3 py-2 shadow-xl ${toast.kind === 'success'
                        ? 'border-emerald-600/60 bg-emerald-900/90 text-emerald-100'
                        : 'border-rose-600/60 bg-rose-900/90 text-rose-100'
                        }`}
                    role="status"
                    aria-live="polite"
                >
                    <div className="flex items-start justify-between gap-3">
                        <p className="text-sm font-medium">{toast.message}</p>
                        <button
                            type="button"
                            onClick={() => removeToast(toast.id)}
                            className="rounded p-1 text-inherit/80 hover:bg-black/20 hover:text-inherit"
                            aria-label="Dismiss notification"
                        >
                            <X size={14} />
                        </button>
                    </div>
                </div>
            ))}
        </div>
    );
}
