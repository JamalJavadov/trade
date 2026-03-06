import axios, { AxiosError } from 'axios';
import { useErrorStore } from '../store/errorStore';
import type { ApiErrorResponse } from '../store/errorStore';

const API_BASE = import.meta.env.VITE_API_BASE_URL || "";

export const apiClient = axios.create({
    baseURL: API_BASE,
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
