import { useEffect } from 'react';
import { getLatestRecommendation, getRecommendationPlaceability } from '../../api/client';
import { playAlertSound } from '../../alerts/alertAudio';
import { shouldTriggerPlaceableAlert } from '../../alerts/alertSignature';
import { useAlertsStore, type ActivePlaceableAlert } from '../../store/alertsStore';

const POLL_INTERVAL_MS = 10_000;

let engineTickInFlight = false;

function maybeShowDesktopNotification(alert: ActivePlaceableAlert): void {
    if (typeof window === 'undefined' || !('Notification' in window)) {
        return;
    }

    if (Notification.permission !== 'granted') {
        return;
    }

    try {
        const notice = new Notification('PLACEABLE TRADE FOUND', {
            body: `${alert.symbol} — ${alert.side} — Placeable now (Mark ok).`,
            tag: `tradebot-placeable-${alert.signature}`,
        });

        notice.onclick = () => {
            window.focus();
            notice.close();
        };
    } catch {
        // Notification failures are non-critical.
    }
}

export const AlertEngine = () => {
    const armed = useAlertsStore((state) => state.armed);

    useEffect(() => {
        let cancelled = false;

        const tick = async () => {
            if (cancelled || engineTickInFlight) {
                return;
            }

            const preState = useAlertsStore.getState();
            preState.clearExpiredSnooze(Date.now());

            const state = useAlertsStore.getState();
            if (!state.armed) {
                return;
            }

            engineTickInFlight = true;
            try {
                const recommendation = await getLatestRecommendation();
                if (cancelled || !recommendation) {
                    return;
                }

                const placeability = await getRecommendationPlaceability(recommendation.id);
                if (cancelled) {
                    return;
                }

                const latestState = useAlertsStore.getState();
                latestState.clearExpiredSnooze(Date.now());

                const decision = shouldTriggerPlaceableAlert({
                    recommendation,
                    placeable: placeability.placeable,
                    armed: latestState.armed,
                    snoozeUntilEpochMs: latestState.snoozeUntilEpochMs,
                    nowEpochMs: Date.now(),
                    lastAlertedSignature: latestState.lastAlertedSignature,
                });

                if (!decision.shouldTrigger || !decision.signature) {
                    return;
                }

                const alert: ActivePlaceableAlert = {
                    recommendationId: recommendation.id,
                    symbol: recommendation.symbol,
                    side: recommendation.side,
                    signature: decision.signature,
                    triggeredAtIso: new Date().toISOString(),
                };

                latestState.triggerAlert(alert);

                const played = playAlertSound({ durationMs: 10_000, volume: latestState.volume });
                useAlertsStore.getState().setAudioBlocked(!played);

                if (latestState.desktopNotificationsEnabled) {
                    maybeShowDesktopNotification(alert);
                }
            } catch {
                // Alert polling/preflight failures should not break UI.
            } finally {
                engineTickInFlight = false;
            }
        };

        const intervalId = window.setInterval(() => {
            if (!useAlertsStore.getState().armed) {
                return;
            }
            void tick();
        }, POLL_INTERVAL_MS);

        if (armed) {
            void tick();
        }

        return () => {
            cancelled = true;
            window.clearInterval(intervalId);
        };
    }, [armed]);

    return null;
};
