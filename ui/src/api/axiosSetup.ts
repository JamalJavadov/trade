import axios, { AxiosError } from 'axios';
import { useErrorStore } from '../store/errorStore';
import type { ApiErrorResponse } from '../store/errorStore';

function normalizeApiBase(rawValue: string | undefined): string {
    if (!rawValue) {
        return '';
    }

    const trimmed = rawValue.trim();
    if (!trimmed || trimmed === '/') {
        return '';
    }

    return trimmed.endsWith('/') ? trimmed.slice(0, -1) : trimmed;
}

function resolveTimeoutMs(rawValue: string | undefined): number {
    if (!rawValue) {
        return 10_000;
    }

    const parsed = Number(rawValue);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : 10_000;
}

export const API_BASE = normalizeApiBase(import.meta.env.VITE_API_BASE_URL);
export const API_TIMEOUT_MS = resolveTimeoutMs(import.meta.env.VITE_API_TIMEOUT_MS);

export function buildApiPath(path: string): string {
    return path.startsWith('/') ? path : `/${path}`;
}

export function buildApiUrl(path: string): string {
    const normalizedPath = buildApiPath(path);
    if (API_BASE) {
        return `${API_BASE}${normalizedPath}`;
    }
    return normalizedPath;
}

export const apiClient = axios.create({
    baseURL: API_BASE || undefined,
    timeout: API_TIMEOUT_MS,
    headers: {
        'Content-Type': 'application/json'
    }
});

function createTraceId(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID();
    }
    return `trace-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

apiClient.interceptors.request.use((config) => {
    const headers = config.headers ?? {};
    const hasTraceHeader = typeof (headers as Record<string, unknown>)['X-Trace-Id'] === 'string';
    if (!hasTraceHeader) {
        (headers as Record<string, unknown>)['X-Trace-Id'] = createTraceId();
    }
    config.headers = headers;
    return config;
});

apiClient.interceptors.response.use(
    (response) => response,
    (error: AxiosError) => {
        if (error.code === 'ECONNABORTED') {
            useErrorStore.getState().addError({
                timestamp: new Date().toISOString(),
                path: error.config?.url || 'network-timeout',
                errorCode: 'TIMEOUT',
                message: `Request timed out after ${API_TIMEOUT_MS}ms`,
                details: 'The backend did not respond before the client timeout elapsed.',
                traceId: null
            });
            return Promise.reject(error);
        }

        if (error.response) {
            const headerTrace = typeof error.response.headers?.['x-trace-id'] === 'string'
                ? error.response.headers['x-trace-id']
                : null;

            if (error.response.data && typeof error.response.data === 'object') {
                const data = error.response.data as Partial<ApiErrorResponse>;
                const traceId = typeof data.traceId === 'string' && data.traceId.trim().length > 0
                    ? data.traceId
                    : (headerTrace && headerTrace.trim().length > 0 ? headerTrace : null);

                if (typeof data.errorCode === 'string' && data.errorCode.trim().length > 0) {
                    useErrorStore.getState().addError({
                        timestamp: data.timestamp || new Date().toISOString(),
                        path: data.path || error.config?.url || 'unknown-path',
                        errorCode: data.errorCode,
                        message: data.message || error.message || 'Error occurred',
                        details: data.details,
                        traceId
                    });
                } else {
                    useErrorStore.getState().addError({
                        timestamp: new Date().toISOString(),
                        path: error.config?.url || 'unknown-path',
                        errorCode: 'INTERNAL',
                        message: error.message,
                        details: error.response.data,
                        traceId
                    });
                }
            } else {
                useErrorStore.getState().addError({
                    timestamp: new Date().toISOString(),
                    path: error.config?.url || 'unknown-path',
                    errorCode: 'INTERNAL',
                    message: error.message,
                    details: typeof error.response.data === 'string' ? error.response.data : 'Non-JSON response received',
                    traceId: headerTrace
                });
            }
        } else {
            useErrorStore.getState().addError({
                timestamp: new Date().toISOString(),
                path: error.config?.url || 'network-error',
                errorCode: 'INTERNAL',
                message: error.message,
                details: 'No response from server',
                traceId: null
            });
        }
        return Promise.reject(error);
    }
);
