import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BinanceCredentialsCard } from './BinanceCredentialsCard';

const { getStatusMock, saveCredentialsMock, testCredentialsMock } = vi.hoisted(() => ({
    getStatusMock: vi.fn(),
    saveCredentialsMock: vi.fn(),
    testCredentialsMock: vi.fn(),
}));

vi.mock('../../api/binanceCredentialsApi', () => ({
    binanceCredentialsApi: {
        getStatus: getStatusMock,
        saveCredentials: saveCredentialsMock,
        testCredentials: testCredentialsMock,
    },
}));

describe('BinanceCredentialsCard', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        getStatusMock.mockResolvedValue({
            status: 'CONFIGURED',
            authMode: 'HMAC_SECRET',
            credentialSource: 'SECURE_UI_SAVED',
            updatedAt: '2026-03-05T12:00:00Z',
        });
        saveCredentialsMock.mockResolvedValue({
            status: 'CONFIGURED',
            authMode: 'HMAC_SECRET',
            credentialSource: 'SECURE_UI_SAVED',
        });
        testCredentialsMock.mockResolvedValue({
            status: 'SUCCESS',
            authMode: 'HMAC_SECRET',
            credentialSource: 'SECURE_UI_SAVED',
            endpointFamily: 'BINANCE_FUTURES',
            executableForLiveFutures: true,
            message: 'Futures account verified. canTrade=true',
        });
    });

    afterEach(() => {
        cleanup();
        vi.restoreAllMocks();
    });

    it('renders broken backend status truthfully', async () => {
        getStatusMock.mockResolvedValue({
            status: 'BROKEN',
            authMode: 'ASYMMETRIC_KEYPAIR',
            credentialSource: 'SECURE_UI_SAVED',
            failureCode: 'CREDENTIAL_DECRYPT_FAILED',
            failureMessage: 'Saved Binance credentials are unreadable. Please re-save them.',
            updatedAt: '2026-03-05T12:00:00Z',
        });

        render(<BinanceCredentialsCard />);

        await waitFor(() => {
            expect(screen.getByText('Needs Repair')).toBeInTheDocument();
        });

        expect(screen.getByText(/Saved Binance credentials are unreadable/i)).toBeInTheDocument();
        expect(screen.getByText('ASYMMETRIC_KEYPAIR')).toBeInTheDocument();
        expect(screen.getByText('CREDENTIAL_DECRYPT_FAILED')).toBeInTheDocument();
    });

    it('tests the active credential source without writing to browser storage', async () => {
        const user = userEvent.setup();
        const storageSpy = vi.spyOn(Storage.prototype, 'setItem');

        render(<BinanceCredentialsCard />);

        await waitFor(() => {
            expect(screen.getByText('Configured')).toBeInTheDocument();
        });

        await user.click(screen.getByRole('button', { name: 'Test Connection' }));

        await waitFor(() => {
            expect(screen.getByText('Connection Test Passed')).toBeInTheDocument();
        });

        expect(testCredentialsMock).toHaveBeenCalledWith(undefined);
        expect(screen.getAllByText('SECURE_UI_SAVED').length).toBeGreaterThan(0);
        expect(storageSpy).not.toHaveBeenCalled();
    });

    it('saves credentials without persisting secrets to browser storage', async () => {
        const user = userEvent.setup();
        const storageSpy = vi.spyOn(Storage.prototype, 'setItem');
        getStatusMock
            .mockResolvedValueOnce({
                status: 'NOT_CONFIGURED',
                credentialSource: 'NOT_CONFIGURED',
            })
            .mockResolvedValueOnce({
                status: 'CONFIGURED',
                authMode: 'HMAC_SECRET',
                credentialSource: 'SECURE_UI_SAVED',
                updatedAt: '2026-03-05T12:00:00Z',
            });

        render(<BinanceCredentialsCard />);

        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Save Credentials' })).toBeInTheDocument();
        });

        await user.type(screen.getByPlaceholderText('Enter API Key (Required)'), 'api-key');
        await user.type(screen.getByPlaceholderText('Enter API Secret'), 'secret-value');
        await user.click(screen.getByRole('button', { name: 'Save Credentials' }));

        await waitFor(() => {
            expect(saveCredentialsMock).toHaveBeenCalledWith({
                apiKey: 'api-key',
                publicKeyPem: '',
                privateKeyPem: 'secret-value',
                authMode: 'HMAC_SECRET',
            });
        });

        expect(storageSpy).not.toHaveBeenCalled();
    });
});
