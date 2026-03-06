export interface AlertSignatureRecommendation {
    id: string;
    symbol: string;
    side: string;
    entryOrder?: {
        stopPrice?: number | string | null;
        price?: number | string | null;
    } | null;
    slOrder?: {
        stopPrice?: number | string | null;
    } | null;
}

export interface AlertTriggerDecisionInput {
    recommendation: AlertSignatureRecommendation | null;
    placeable: boolean;
    armed: boolean;
    snoozeUntilEpochMs: number | null;
    nowEpochMs: number;
    lastAlertedSignature: string | null;
}

export type AlertTriggerReason =
    | 'trigger'
    | 'no_recommendation'
    | 'not_armed'
    | 'snoozed'
    | 'not_placeable'
    | 'duplicate_signature';

export interface AlertTriggerDecision {
    shouldTrigger: boolean;
    reason: AlertTriggerReason;
    signature: string | null;
}

function toField(value: unknown, fallback: string): string {
    if (value === null || value === undefined) {
        return fallback;
    }
    return String(value);
}

export function buildRecommendationSignature(recommendation: AlertSignatureRecommendation): string {
    const entry =
        recommendation.entryOrder?.stopPrice ?? recommendation.entryOrder?.price ?? 'MARKET';
    const sl = recommendation.slOrder?.stopPrice ?? '';

    return `${recommendation.id}|${recommendation.symbol}|${recommendation.side}|${toField(entry, 'MARKET')}|${toField(sl, '')}`;
}

export function shouldTriggerPlaceableAlert(input: AlertTriggerDecisionInput): AlertTriggerDecision {
    const {
        recommendation,
        placeable,
        armed,
        snoozeUntilEpochMs,
        nowEpochMs,
        lastAlertedSignature,
    } = input;

    if (!recommendation) {
        return { shouldTrigger: false, reason: 'no_recommendation', signature: null };
    }

    if (!armed) {
        return { shouldTrigger: false, reason: 'not_armed', signature: null };
    }

    if (snoozeUntilEpochMs != null && snoozeUntilEpochMs > nowEpochMs) {
        return { shouldTrigger: false, reason: 'snoozed', signature: null };
    }

    if (!placeable) {
        return { shouldTrigger: false, reason: 'not_placeable', signature: null };
    }

    const signature = buildRecommendationSignature(recommendation);
    if (signature === lastAlertedSignature) {
        return { shouldTrigger: false, reason: 'duplicate_signature', signature };
    }

    return {
        shouldTrigger: true,
        reason: 'trigger',
        signature,
    };
}
