export const ErrorExplainMap: Record<string, { why: string; fix: string }> = {
    'BINANCE_RATE_LIMIT': {
        why: 'Binance rejected requests due to rate limits.',
        fix: 'Increase scan interval, reduce concurrency, or retry after the limit resets.',
    },
    'BINANCE_AUTH': {
        why: 'API key invalid or permissions missing.',
        fix: 'Check BINANCE_API_KEY/SECRET, verify futures read permissions in Binance console.',
    },
    'BINANCE_NETWORK': {
        why: 'Binance network request timed out or failed.',
        fix: 'Retry shortly. If persistent, check outbound network/DNS and Binance status.',
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
