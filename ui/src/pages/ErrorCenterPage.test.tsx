import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ErrorCenterPage } from './ErrorCenterPage';
import { useErrorStore } from '../store/errorStore';

const writeTextMock = vi.fn();

describe('ErrorCenterPage', () => {
    beforeEach(() => {
        writeTextMock.mockReset();
        Object.defineProperty(navigator, 'clipboard', {
            value: {
                writeText: writeTextMock,
            },
            configurable: true,
        });

        useErrorStore.setState({
            errors: [],
            liveScanContext: null,
        });
    });

    afterEach(() => {
        cleanup();
        useErrorStore.setState({
            errors: [],
            liveScanContext: null,
        });
    });

    it('shows full trace id and copies it with Copy Trace action', async () => {
        const traceId = 'trace-12345678-full-value-abcdef';
        useErrorStore.setState({
            errors: [{
                id: 'err-1',
                resolved: false,
                timestamp: '2026-03-01T10:00:00Z',
                path: '/api/v1/recommendations/latest',
                errorCode: 'DB_DOWN',
                message: 'Database unavailable',
                details: { rootMessage: 'Connection refused' },
                traceId,
            }],
        });

        render(<ErrorCenterPage />);

        expect(screen.getByText(`Trace: ${traceId}`)).toBeInTheDocument();

        const copyTraceButton = screen.getByTitle('Copy Trace ID');
        copyTraceButton.click();
        expect(writeTextMock).toHaveBeenCalledWith(traceId);
    });

    it('shows missing trace placeholder and disables trace copy action', () => {
        useErrorStore.setState({
            errors: [{
                id: 'err-2',
                resolved: false,
                timestamp: '2026-03-01T10:00:00Z',
                path: '/api/v1/recommendations/latest',
                errorCode: 'INTERNAL',
                message: 'Unknown failure',
                details: null,
                traceId: null,
            }],
        });

        render(<ErrorCenterPage />);

        expect(screen.getByText('Trace: (no trace id)')).toBeInTheDocument();
        const disabledTraceButton = screen.getByTitle('Trace ID unavailable');
        expect(disabledTraceButton).toBeDisabled();
    });
});
