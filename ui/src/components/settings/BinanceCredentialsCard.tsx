import React, { useState, useEffect } from 'react';
import { binanceCredentialsApi, type BinanceCredentialRequest, type BinanceCredentialResponse } from '../../api/binanceCredentialsApi';

function normalizeAuthModeValue(value?: string): BinanceCredentialRequest['authMode'] | null {
    if (value === 'HMAC_SECRET' || value === 'ASYMMETRIC_KEYPAIR') {
        return value;
    }
    return null;
}

export function BinanceCredentialsCard() {
    const [status, setStatus] = useState<BinanceCredentialResponse | null>(null);
    const [loading, setLoading] = useState(false);
    const [testing, setTesting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);
    const [isEditing, setIsEditing] = useState(false);

    const [formData, setFormData] = useState<BinanceCredentialRequest>({
        apiKey: '',
        publicKeyPem: '',
        privateKeyPem: '',
        authMode: 'HMAC_SECRET',
    });

    useEffect(() => {
        loadStatus();
    }, []);

    const loadStatus = async () => {
        try {
            const result = await binanceCredentialsApi.getStatus();
            setStatus(result);
            setFormData(prev => ({
                ...prev,
                authMode: normalizeAuthModeValue(result.authMode) ?? prev.authMode,
            }));
            setIsEditing(result.status === 'NOT_CONFIGURED' || result.status === 'BROKEN');
        } catch (err: any) {
            setError(err?.response?.data?.message || 'Failed to load credentials status');
        }
    };

    const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
        const { name, value } = e.target;
        setFormData(prev => ({ ...prev, [name]: value }));
    };

    const clearMessages = () => {
        setError(null);
        setSuccess(null);
    };

    const handleSave = async (e: React.FormEvent) => {
        e.preventDefault();
        clearMessages();
        setLoading(true);
        try {
            await binanceCredentialsApi.saveCredentials(formData);
            setSuccess('Credentials saved successfully');
            setFormData({ apiKey: '', publicKeyPem: '', privateKeyPem: '', authMode: formData.authMode });
            await loadStatus();
            setIsEditing(false);
        } catch (err: any) {
            setError(err?.response?.data?.message || 'Failed to save credentials');
        } finally {
            setLoading(false);
        }
    };

    const [testResult, setTestResult] = useState<import('../../api/binanceCredentialsApi').BinanceCredentialResponse | null>(null);

    const handleTest = async () => {
        clearMessages();
        setTestResult(null);
        setTesting(true);
        try {
            const isPartialOrFullForm = formData.apiKey || formData.privateKeyPem;
            const requestPayload = isEditing && isPartialOrFullForm ? formData : undefined;

            const result = await binanceCredentialsApi.testCredentials(requestPayload);
            setTestResult(result);
            if (result.status === 'SUCCESS') {
                setSuccess(result.message || 'Connection test successful');
            } else {
                setError(result.failureMessage || result.message || 'Connection test failed');
            }
        } catch (err: any) {
            setError(err?.response?.data?.message || 'Connection test failed');
        } finally {
            setTesting(false);
        }
    };

    const handleCancel = () => {
        setIsEditing(false);
        setFormData({
            apiKey: '',
            publicKeyPem: '',
            privateKeyPem: '',
            authMode: normalizeAuthModeValue(status?.authMode) || 'HMAC_SECRET',
        });
        clearMessages();
    };

    const activeError = error || (status?.status === 'BROKEN' ? status.failureMessage || 'Saved Binance credentials are unreadable. Please re-save them.' : null);

    return (
        <div className="bg-slate-800 p-6 rounded-lg text-slate-100 shadow-md">
            <h2 className="text-xl font-bold mb-4">Binance API Credentials</h2>

            <div className="mb-6 p-4 bg-slate-700 rounded text-sm">
                <p>
                    <strong>Status:</strong>{' '}
                    {status?.status === 'CONFIGURED'
                        ? <span className="text-green-400">Configured</span>
                        : status?.status === 'BROKEN'
                            ? <span className="text-red-400">Needs Repair</span>
                            : <span className="text-yellow-400">Not Configured</span>}
                </p>
                {status?.authMode && <p><strong>Auth Mode:</strong> {status.authMode}</p>}
                {status?.credentialSource && (
                    <p>
                        <strong>Credential Source:</strong>{' '}
                        {status.credentialSource === 'YAML_LEGACY'
                            ? <span className="text-orange-400">YAML (legacy) — save here to move to local backend storage</span>
                            : status.credentialSource === 'SECURE_UI_SAVED'
                                ? <span className="text-green-400">Local backend (UI-saved)</span>
                                : status.credentialSource === 'REQUEST_PAYLOAD'
                                    ? <span className="text-blue-300">Unsaved test payload</span>
                                : <span className="text-slate-300">{status.credentialSource}</span>}
                    </p>
                )}
                {status?.updatedAt && <p><strong>Last Updated:</strong> {new Date(status.updatedAt).toLocaleString()}</p>}
                {status?.status === 'BROKEN' && status.failureCode && <p><strong>Failure Code:</strong> <span className="text-red-300">{status.failureCode}</span></p>}
            </div>

            {activeError && <div className="mb-4 p-3 bg-red-900/50 text-red-200 border border-red-800 rounded">{activeError}</div>}
            {success && <div className="mb-4 p-3 bg-green-900/50 text-green-200 border border-green-800 rounded">{success}</div>}

            {testResult && testResult.status === 'FAILED' && (
                <div className="mb-4 p-4 bg-slate-900 border border-red-700 rounded text-sm font-mono space-y-1">
                    <p className="text-red-300 font-semibold text-xs uppercase tracking-wide mb-2">Connection Test Diagnostics</p>
                    {testResult.failureCode && <p><span className="text-slate-400">Failure Code:</span> <span className="text-yellow-300">{testResult.failureCode}</span></p>}
                    {testResult.endpointFamily && <p><span className="text-slate-400">Endpoint Family:</span> <span className="text-slate-200">{testResult.endpointFamily}</span></p>}
                    {testResult.credentialSource && <p><span className="text-slate-400">Credential Source:</span> <span className="text-slate-200">{testResult.credentialSource}</span></p>}
                    {testResult.authMode && <p><span className="text-slate-400">Auth Mode Tested:</span> <span className="text-slate-200">{testResult.authMode}</span></p>}
                    {testResult.safeDetails && <p><span className="text-slate-400">Binance Details:</span> <span className="text-slate-300">{testResult.safeDetails}</span></p>}
                    <p className="text-slate-500 text-xs mt-2">If credentials are placeholder/demo values, this failure is expected.</p>
                </div>
            )}
            {testResult && testResult.status === 'SUCCESS' && (
                <div className="mb-4 p-4 bg-slate-900 border border-green-700 rounded text-sm font-mono space-y-1">
                    <p className="text-green-300 font-semibold text-xs uppercase tracking-wide mb-2">Connection Test Passed</p>
                    {testResult.endpointFamily && <p><span className="text-slate-400">Endpoint Family:</span> <span className="text-slate-200">{testResult.endpointFamily}</span></p>}
                    {testResult.credentialSource && <p><span className="text-slate-400">Credential Source:</span> <span className="text-slate-200">{testResult.credentialSource}</span></p>}
                    {testResult.authMode && <p><span className="text-slate-400">Auth Mode:</span> <span className="text-slate-200">{testResult.authMode}</span></p>}
                    {testResult.executableForLiveFutures !== undefined && <p><span className="text-slate-400">Eligible for Live Futures:</span> <span className={testResult.executableForLiveFutures ? 'text-green-400' : 'text-red-400'}>{testResult.executableForLiveFutures ? 'Yes' : 'No'}</span></p>}
                </div>
            )}

            {status?.status === 'CONFIGURED' && !isEditing ? (
                <div className="space-y-4">
                    <p className="text-sm text-slate-300">Your Binance API credentials are stored on the local backend and are never returned by the API. Values cannot be viewed once saved.</p>
                    <div className="flex space-x-4">
                        <button
                            type="button"
                            onClick={() => setIsEditing(true)}
                            className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded font-medium transition-colors"
                        >
                            Edit Credentials
                        </button>
                        <button
                            type="button"
                            onClick={handleTest}
                            disabled={testing}
                            className="px-4 py-2 bg-slate-600 hover:bg-slate-500 text-white rounded font-medium transition-colors disabled:opacity-50"
                        >
                            {testing ? 'Testing...' : 'Test Connection'}
                        </button>
                    </div>
                </div>
            ) : (
                <form onSubmit={handleSave} className="space-y-4">
                    <div className="text-sm text-yellow-200 mb-4 bg-yellow-900/30 p-3 rounded">
                        {status?.status === 'BROKEN'
                            ? 'Stored credentials are broken or unreadable. Re-save the API key and secret/private key below to repair them.'
                            : status?.status === 'CONFIGURED'
                            ? 'Editing mode. Leave fields blank to keep existing configured keys. Provide an API key or Private key only if you wish to overwrite them. Type "CLEAR" in the Public Key field to remove it.'
                            : 'Please provide your Binance credentials below. Secrets stay on the local backend and are never returned by the API.'}
                    </div>
                    <div>
                        <label className="block text-sm font-medium mb-1">Auth Mode *</label>
                        <select
                            name="authMode"
                            value={formData.authMode}
                            onChange={handleInputChange}
                            className="w-full bg-slate-900 border border-slate-600 rounded p-2 focus:ring-2 focus:ring-blue-500 outline-none"
                            required
                        >
                            <option value="HMAC_SECRET">API Key + Secret Key (HMAC)</option>
                            <option value="ASYMMETRIC_KEYPAIR">API Key + Private Key (RSA / Ed25519)</option>
                        </select>
                    </div>

                    <div>
                        <label className="block text-sm font-medium mb-1">API Key {status?.status !== 'CONFIGURED' && '*'}</label>
                        <input
                            type="text"
                            name="apiKey"
                            value={formData.apiKey}
                            onChange={handleInputChange}
                            className="w-full bg-slate-900 border border-slate-600 rounded p-2 focus:ring-2 focus:ring-blue-500 outline-none font-mono text-sm"
                            placeholder={status?.status === 'CONFIGURED' ? 'Leave blank to keep existing API Key' : 'Enter API Key (Required)'}
                            required={status?.status !== 'CONFIGURED'}
                        />
                    </div>

                    {formData.authMode === 'ASYMMETRIC_KEYPAIR' && (
                        <div>
                            <label className="block text-sm font-medium mb-1">Public Key PEM (Optional)</label>
                            <textarea
                                name="publicKeyPem"
                                value={formData.publicKeyPem}
                                onChange={handleInputChange}
                                className="w-full h-24 bg-slate-900 border border-slate-600 rounded p-2 focus:ring-2 focus:ring-blue-500 outline-none font-mono text-xs"
                                placeholder={status?.status === 'CONFIGURED' ? 'Leave blank to keep existing, or type CLEAR to remove' : '-----BEGIN PUBLIC KEY-----...'}
                            />
                        </div>
                    )}

                    <div>
                        <label className="block text-sm font-medium mb-1">
                            {formData.authMode === 'ASYMMETRIC_KEYPAIR' ? `Private Key PEM ${status?.status !== 'CONFIGURED' ? '*' : ''}` : `API Secret ${status?.status !== 'CONFIGURED' ? '*' : ''}`}
                        </label>
                        <textarea
                            name="privateKeyPem"
                            value={formData.privateKeyPem}
                            onChange={handleInputChange}
                            className="w-full h-32 bg-slate-900 border border-slate-600 rounded p-2 focus:ring-2 focus:ring-blue-500 outline-none font-mono text-xs"
                            placeholder={status?.status === 'CONFIGURED' ? `Leave blank to keep existing ${formData.authMode === 'ASYMMETRIC_KEYPAIR' ? 'Private Key' : 'Secret'}` : (formData.authMode === 'ASYMMETRIC_KEYPAIR' ? '-----BEGIN PRIVATE KEY-----...' : 'Enter API Secret')}
                            required={status?.status !== 'CONFIGURED'}
                        />
                    </div>

                    <div className="flex flex-wrap gap-4 pt-4">
                        <button
                            type="submit"
                            disabled={loading || (status?.status !== 'CONFIGURED' && (!formData.apiKey || !formData.privateKeyPem))}
                            className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded font-medium transition-colors disabled:opacity-50"
                        >
                            {loading ? 'Saving...' : 'Save Credentials'}
                        </button>
                        {status?.status === 'CONFIGURED' && (
                            <button
                                type="button"
                                onClick={handleCancel}
                                disabled={loading || testing}
                                className="px-4 py-2 bg-slate-600 hover:bg-slate-500 text-white rounded font-medium transition-colors disabled:opacity-50"
                            >
                                Cancel
                            </button>
                        )}
                        <button
                            type="button"
                            onClick={handleTest}
                            disabled={testing}
                            className="px-4 py-2 bg-transparent border border-slate-500 hover:bg-slate-700 text-slate-300 rounded font-medium transition-colors disabled:opacity-50"
                        >
                            {testing ? 'Testing...' : 'Test Connection'}
                        </button>
                    </div>
                </form>
            )}
        </div>
    );
}
