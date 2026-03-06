import axios from 'axios';

export interface ParsedApiError {
    errorCode: string;
    message: string;
    traceId: string | null;
    fieldErrors: Record<string, string>;
}

function toFieldErrors(details: unknown): Record<string, string> {
    if (!details || typeof details !== 'object') {
        return {};
    }

    const detailsRecord = details as Record<string, unknown>;
    const nested = detailsRecord.fieldErrors;
    if (nested && typeof nested === 'object') {
        return Object.entries(nested as Record<string, unknown>).reduce<Record<string, string>>((acc, [key, value]) => {
            if (typeof value === 'string') {
                acc[key] = value;
            }
            return acc;
        }, {});
    }

    return Object.entries(detailsRecord).reduce<Record<string, string>>((acc, [key, value]) => {
        if (typeof value === 'string') {
            acc[key] = value;
        }
        return acc;
    }, {});
}

export function parseApiError(error: unknown): ParsedApiError {
    if (axios.isAxiosError(error)) {
        const data = (error.response?.data ?? {}) as Record<string, unknown>;
        const traceIdHeader = error.response?.headers?.['x-trace-id'];
        const traceId = typeof data.traceId === 'string'
            ? data.traceId
            : (typeof traceIdHeader === 'string' ? traceIdHeader : null);

        return {
            errorCode: typeof data.errorCode === 'string' ? data.errorCode : 'INTERNAL',
            message: typeof data.message === 'string' ? data.message : error.message,
            traceId,
            fieldErrors: toFieldErrors(data.details)
        };
    }

    if (error instanceof Error) {
        return {
            errorCode: 'INTERNAL',
            message: error.message,
            traceId: null,
            fieldErrors: {}
        };
    }

    return {
        errorCode: 'INTERNAL',
        message: 'Unknown error',
        traceId: null,
        fieldErrors: {}
    };
}
