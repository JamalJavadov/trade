export const ErrorExplainMap: Record<string, { why: string; fix: string }> = {
    'BINANCE_RATE_LIMIT': {
        why: 'Binance rejected requests due to rate limits.',
        fix: 'Increase scan interval, reduce concurrency, or retry after the limit resets.',
    },
    'BINANCE_AUTH': {
        why: 'API key invalid or permissions missing.',
        fix: 'Check BINANCE_API_KEY/SECRET, verify futures read permissions in Binance console.',
    },
    'BINANCE_AUTH_INVALID': {
        why: 'Signed Binance authentication failed.',
        fix: 'Check the selected Binance API key, matching secret/private key, Futures permission, and any Binance IP restrictions.',
    },
    'BINANCE_IP_NOT_ALLOWED': {
        why: 'Binance rejected the backend host IP for signed requests.',
        fix: 'Update the Binance trusted IP allowlist to include the backend host IP reported in diagnostics.',
    },
    'BINANCE_FUTURES_PERMISSION_MISSING': {
        why: 'The key works for signed requests, but Binance Futures access is not enabled.',
        fix: 'Enable Futures permissions for the API key in Binance and wait for the permission change to propagate.',
    },
    'BINANCE_TIMESTAMP_INVALID': {
        why: 'The backend clock is outside Binance recvWindow.',
        fix: 'Correct the workstation/server time and retry after NTP or system clock sync.',
    },
    'BINANCE_SIGNING_FAILED': {
        why: 'The backend could not complete a valid Binance signature for the selected credential type.',
        fix: 'Verify the saved secret or private key matches the selected auth mode and re-save the credentials if needed.',
    },
    'CREDENTIAL_DECRYPT_FAILED': {
        why: 'A saved Binance credential record exists, but the backend can no longer read it.',
        fix: 'Re-save the Binance credentials in Control Center to repair the local record.',
    },
    'CREDENTIAL_RECORD_CORRUPT': {
        why: 'The saved Binance credential record is incomplete or internally inconsistent.',
        fix: 'Re-save the Binance credentials in Control Center so the API key and secret/private key match the selected auth mode.',
    },
    'CREDENTIAL_AUTH_MODE_UNKNOWN': {
        why: 'The saved Binance credential auth mode is invalid or missing.',
        fix: 'Update the Binance credentials in Control Center and select the correct auth mode before saving.',
    },
    'CREDENTIAL_SOURCE_MISMATCH': {
        why: 'The unsaved test payload does not match the active saved auth mode.',
        fix: 'Either test the saved record as-is or re-enter the full credential set for the selected auth mode.',
    },
    'USER_CONFIGURATION_MISMATCH': {
        why: 'The saved asymmetric Binance private key is malformed or does not match a supported Binance key type.',
        fix: 'Re-save the Binance credentials with the matching PKCS#8 RSA or Ed25519 private key registered on Binance.',
    },
    'PLACEHOLDER_CREDENTIALS_DETECTED': {
        why: 'The configured Binance values look like placeholder or demo credentials.',
        fix: 'Replace them with the real local Binance API key and matching secret/private key before testing again.',
    },
    'BINANCE_ENDPOINT_MISCONFIGURED': {
        why: 'The backend is calling the wrong Binance endpoint family or path.',
        fix: 'Use the live-trading health diagnostics to verify Futures endpoints are being used for signed checks.',
    },
    'BINANCE_NETWORK': {
        why: 'Binance network request timed out or failed.',
        fix: 'Retry shortly. If persistent, check outbound network/DNS and Binance status.',
    },
    'LIVE_EXECUTION_DISABLED': {
        why: 'Manual live execution is disabled in runtime config.',
        fix: 'Enable `live.execution.enabled` in Control Center.',
    },
    'BOT_READ_ONLY': {
        why: 'Trading is disabled because live execution is in READ-ONLY mode.',
        fix: 'Turn off the Control Center live execution READ-ONLY toggle before submitting a real order.',
    },
    'LOCAL_MUTATION_BLOCKED': {
        why: 'Live execution mutations are only allowed from localhost.',
        fix: 'Open the UI from localhost and submit from the same workstation as the backend.',
    },
    'RUNTIME_PERMISSION_DISABLED': {
        why: 'A required runtime permission is disabled.',
        fix: 'Enable the required permission in Control Center and retry.',
    },
    'DUPLICATE_SUBMIT_BLOCKED': {
        why: 'This manual live execution request was already submitted.',
        fix: 'Refresh execution history before retrying. Use a new client request id for a new manual attempt.',
    },
    'PLACEABILITY_FAILED': {
        why: 'The recommendation is no longer placeable against live market conditions.',
        fix: 'Refresh the recommendation or wait for a new one instead of forcing a stale setup.',
    },
    'RECOMMENDATION_STALE': {
        why: 'The recommendation is too old for manual live execution.',
        fix: 'Use a newly generated recommendation before submitting a live order.',
    },
    'EXCHANGE_FILTER_INVALID': {
        why: 'The prepared order payload no longer satisfies Binance Futures filters.',
        fix: 'Refresh the live preflight and verify quantity, tick-size, and notional rules before retrying.',
    },
    'OPENROUTER_AUTH': {
        why: 'OpenRouter key invalid.',
        fix: 'Check OPENROUTER_API_KEY and verify model names in the config.',
    },
    'OPENROUTER_RATE_LIMIT': {
        why: 'OpenRouter rejected requests due to rate limits.',
        fix: 'Retry after the provider cooldown or lower request frequency.',
    },
    'OPENROUTER_NETWORK': {
        why: 'OpenRouter network request timed out or failed.',
        fix: 'Retry shortly and verify outbound network connectivity.',
    },
    'DB_DOWN': {
        why: 'Postgres unreachable.',
        fix: 'Start the Postgres daemon, check the connection string.',
    },
    'SCANNER_DOWN': {
        why: 'Scanner worker is temporarily unavailable.',
        fix: 'Retry shortly. If it repeats, check backend worker thread pool saturation and server health.',
    },
    'VALIDATION': {
        why: 'Invalid input fields submitted.',
        fix: 'Correct the highlighted fields and try saving again.',
    },
    'SCAN_RUNNING': {
        why: 'Settings change conflicts with an active scan run.',
        fix: 'Wait for the active scan to finish, then retry the scheduling change.',
    },
    'SAFE_MODE': {
        why: 'Safe mode blocks this operation.',
        fix: 'Disable Safe Mode if you intentionally want this action to proceed.',
    },
    'NOT_FOUND': {
        why: 'Requested resource was not found.',
        fix: 'Refresh and retry. If it persists, verify the resource ID or endpoint path.',
    },
    'INTERNAL': {
        why: 'Unexpected server error.',
        fix: 'Copy the Error Report using the traceId and check the backend logs.',
    },
    'SSE_DISCONNECT': {
        why: 'Network dropped or backend server restarted.',
        fix: 'The UI will auto-reconnect. If the issue persists, restart the backend server manually.',
    },
    'CONFLICT_OR_STATE_ERROR': {
        why: 'System state prevents this action.',
        fix: 'Refresh the page and verify the backend status before retrying.',
    },
    'BAD_REQUEST': {
        why: 'Malformed data sent to the server.',
        fix: 'Check your inputs or try refreshing the page.',
    },
    'UI_RUNTIME_ERROR': {
        why: 'The Live Scan page hit an unhandled runtime error.',
        fix: 'Use "Reload scan details" and share the copied crash report if the issue repeats.',
    },
    'DEFAULT': {
        why: 'An unknown error occurred.',
        fix: 'View the Error Report and consult the runbook or backend logs.',
    }
};

export const getErrorExplanation = (errorCode: string) => {
    return ErrorExplainMap[errorCode] || ErrorExplainMap['DEFAULT'];
};
