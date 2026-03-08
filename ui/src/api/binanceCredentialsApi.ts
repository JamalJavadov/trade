import { apiClient } from './axiosSetup';

export interface BinanceCredentialRequest {
    apiKey: string;
    publicKeyPem?: string;
    privateKeyPem?: string;
    authMode: 'HMAC_SECRET' | 'ASYMMETRIC_KEYPAIR';
}

export interface BinanceCredentialResponse {
    status: 'CONFIGURED' | 'BROKEN' | 'NOT_CONFIGURED' | 'SUCCESS' | 'FAILED';
    authMode?: string;
    credentialSource?: 'SECURE_UI_SAVED' | 'YAML_LEGACY' | 'NOT_CONFIGURED' | 'REQUEST_PAYLOAD';
    updatedAt?: string;
    message?: string;
    endpointFamily?: string;
    executableForLiveFutures?: boolean;
    failureCode?: string;
    failureMessage?: string;
    safeDetails?: string;
}

export const binanceCredentialsApi = {
    getStatus: async (): Promise<BinanceCredentialResponse> => {
        const response = await apiClient.get<BinanceCredentialResponse>('/api/v1/integrations/binance/credentials/status');
        return response.data;
    },

    saveCredentials: async (request: BinanceCredentialRequest): Promise<BinanceCredentialResponse> => {
        const response = await apiClient.post<BinanceCredentialResponse>('/api/v1/integrations/binance/credentials', request);
        return response.data;
    },

    testCredentials: async (request?: BinanceCredentialRequest): Promise<BinanceCredentialResponse> => {
        const response = await apiClient.post<BinanceCredentialResponse>('/api/v1/integrations/binance/credentials/test', request);
        return response.data;
    }
};
