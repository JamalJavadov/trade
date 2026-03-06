import { describe, expect, it } from 'vitest';
import {
    buildRecommendationSignature,
    shouldTriggerPlaceableAlert,
    type AlertSignatureRecommendation,
} from './alertSignature';

function makeRecommendation(overrides?: Partial<AlertSignatureRecommendation>): AlertSignatureRecommendation {
    return {
        id: 'rec-1',
        symbol: 'BTCUSDT',
        side: 'BUY',
        entryOrder: {
            stopPrice: '123.4',
            price: '123.4',
        },
        slOrder: {
            stopPrice: '120.0',
        },
        ...overrides,
    };
}

describe('buildRecommendationSignature', () => {
    it('returns same signature for same recommendation input', () => {
        const rec = makeRecommendation();
        const a = buildRecommendationSignature(rec);
        const b = buildRecommendationSignature(rec);

        expect(a).toBe(b);
    });

    it('changes signature when recommendation id changes', () => {
        const base = makeRecommendation();
        const changed = makeRecommendation({ id: 'rec-2' });

        expect(buildRecommendationSignature(base)).not.toBe(buildRecommendationSignature(changed));
    });

    it('changes signature when symbol changes', () => {
        const base = makeRecommendation();
        const changed = makeRecommendation({ symbol: 'ETHUSDT' });

        expect(buildRecommendationSignature(base)).not.toBe(buildRecommendationSignature(changed));
    });

    it('changes signature when side changes', () => {
        const base = makeRecommendation();
        const changed = makeRecommendation({ side: 'SELL' });

        expect(buildRecommendationSignature(base)).not.toBe(buildRecommendationSignature(changed));
    });

    it('changes signature when entry changes', () => {
        const base = makeRecommendation();
        const changed = makeRecommendation({
            entryOrder: {
                stopPrice: '124.0',
                price: '124.0',
            },
        });

        expect(buildRecommendationSignature(base)).not.toBe(buildRecommendationSignature(changed));
    });

    it('changes signature when stop loss changes', () => {
        const base = makeRecommendation();
        const changed = makeRecommendation({
            slOrder: {
                stopPrice: '119.5',
            },
        });

        expect(buildRecommendationSignature(base)).not.toBe(buildRecommendationSignature(changed));
    });
});

describe('shouldTriggerPlaceableAlert', () => {
    it('returns no trigger for duplicate signature', () => {
        const recommendation = makeRecommendation();
        const signature = buildRecommendationSignature(recommendation);

        const decision = shouldTriggerPlaceableAlert({
            recommendation,
            placeable: true,
            armed: true,
            snoozeUntilEpochMs: null,
            nowEpochMs: 1_000,
            lastAlertedSignature: signature,
        });

        expect(decision.shouldTrigger).toBe(false);
        expect(decision.reason).toBe('duplicate_signature');
    });

    it('returns trigger for new signature when armed unsnoozed and placeable', () => {
        const recommendation = makeRecommendation();

        const decision = shouldTriggerPlaceableAlert({
            recommendation,
            placeable: true,
            armed: true,
            snoozeUntilEpochMs: null,
            nowEpochMs: 1_000,
            lastAlertedSignature: 'another-signature',
        });

        expect(decision.shouldTrigger).toBe(true);
        expect(decision.reason).toBe('trigger');
        expect(decision.signature).toBe(buildRecommendationSignature(recommendation));
    });

    it('returns no trigger when recommendation is not placeable', () => {
        const decision = shouldTriggerPlaceableAlert({
            recommendation: makeRecommendation(),
            placeable: false,
            armed: true,
            snoozeUntilEpochMs: null,
            nowEpochMs: 1_000,
            lastAlertedSignature: null,
        });

        expect(decision.shouldTrigger).toBe(false);
        expect(decision.reason).toBe('not_placeable');
    });

    it('returns no trigger when disarmed', () => {
        const decision = shouldTriggerPlaceableAlert({
            recommendation: makeRecommendation(),
            placeable: true,
            armed: false,
            snoozeUntilEpochMs: null,
            nowEpochMs: 1_000,
            lastAlertedSignature: null,
        });

        expect(decision.shouldTrigger).toBe(false);
        expect(decision.reason).toBe('not_armed');
    });

    it('returns no trigger when snoozed', () => {
        const decision = shouldTriggerPlaceableAlert({
            recommendation: makeRecommendation(),
            placeable: true,
            armed: true,
            snoozeUntilEpochMs: 2_000,
            nowEpochMs: 1_000,
            lastAlertedSignature: null,
        });

        expect(decision.shouldTrigger).toBe(false);
        expect(decision.reason).toBe('snoozed');
    });
});
