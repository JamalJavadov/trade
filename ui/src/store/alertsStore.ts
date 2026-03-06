import { create } from 'zustand';

const ALERT_SETTINGS_STORAGE_KEY = 'tradebot_alert_settings_v1';
const ALERT_LOCK_MS = 10_000;

export interface ActivePlaceableAlert {
    recommendationId: string;
    symbol: string;
    side: string;
    signature: string;
    triggeredAtIso: string;
}

interface PersistedAlertSettings {
    armed: boolean;
    volume: number;
    desktopNotificationsEnabled: boolean;
    snoozeUntilEpochMs: number | null;
    lastAlertedSignature: string | null;
    lastAlertedAtIso: string | null;
}

interface AlertsState extends PersistedAlertSettings {
    audioBlocked: boolean;
    notificationBlocked: boolean;
    modalAlert: ActivePlaceableAlert | null;
    bannerAlert: ActivePlaceableAlert | null;
    modalLockUntilEpochMs: number | null;

    setArmed: (value: boolean) => void;
    setVolume: (value: number) => void;
    setDesktopNotificationsEnabled: (value: boolean) => void;
    setSnoozeUntil: (value: number | null) => void;
    clearExpiredSnooze: (nowEpochMs?: number) => void;
    setAudioBlocked: (value: boolean) => void;
    setNotificationBlocked: (value: boolean) => void;

    triggerAlert: (alert: ActivePlaceableAlert) => void;
    dismissModal: () => void;
    dismissBanner: () => void;
    snoozeForMinutes: (minutes: number) => void;
}

const defaultPersistedSettings: PersistedAlertSettings = {
    armed: false,
    volume: 0.9,
    desktopNotificationsEnabled: false,
    snoozeUntilEpochMs: null,
    lastAlertedSignature: null,
    lastAlertedAtIso: null,
};

function clampVolume(value: unknown): number {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) {
        return defaultPersistedSettings.volume;
    }
    return Math.min(1, Math.max(0, numeric));
}

function readPersistedSettings(): PersistedAlertSettings {
    if (typeof window === 'undefined') {
        return defaultPersistedSettings;
    }

    try {
        const raw = window.localStorage.getItem(ALERT_SETTINGS_STORAGE_KEY);
        if (!raw) {
            return defaultPersistedSettings;
        }

        const parsed = JSON.parse(raw) as Partial<PersistedAlertSettings>;
        return {
            armed: Boolean(parsed.armed),
            volume: clampVolume(parsed.volume),
            desktopNotificationsEnabled: Boolean(parsed.desktopNotificationsEnabled),
            snoozeUntilEpochMs:
                typeof parsed.snoozeUntilEpochMs === 'number' ? parsed.snoozeUntilEpochMs : null,
            lastAlertedSignature:
                typeof parsed.lastAlertedSignature === 'string' ? parsed.lastAlertedSignature : null,
            lastAlertedAtIso:
                typeof parsed.lastAlertedAtIso === 'string' ? parsed.lastAlertedAtIso : null,
        };
    } catch {
        return defaultPersistedSettings;
    }
}

function persistSettings(state: PersistedAlertSettings): void {
    if (typeof window === 'undefined') {
        return;
    }

    try {
        window.localStorage.setItem(ALERT_SETTINGS_STORAGE_KEY, JSON.stringify(state));
    } catch {
        // Ignore localStorage write failures
    }
}

function toPersisted(state: AlertsState): PersistedAlertSettings {
    return {
        armed: state.armed,
        volume: state.volume,
        desktopNotificationsEnabled: state.desktopNotificationsEnabled,
        snoozeUntilEpochMs: state.snoozeUntilEpochMs,
        lastAlertedSignature: state.lastAlertedSignature,
        lastAlertedAtIso: state.lastAlertedAtIso,
    };
}

const initialPersisted = readPersistedSettings();

export const useAlertsStore = create<AlertsState>((set, get) => ({
    ...initialPersisted,
    audioBlocked: false,
    notificationBlocked: false,
    modalAlert: null,
    bannerAlert: null,
    modalLockUntilEpochMs: null,

    setArmed: (value) => {
        set((state) => {
            const next: AlertsState = {
                ...state,
                armed: value,
                audioBlocked: value ? state.audioBlocked : false,
            };
            persistSettings(toPersisted(next));
            return next;
        });
    },

    setVolume: (value) => {
        set((state) => {
            const next: AlertsState = {
                ...state,
                volume: clampVolume(value),
            };
            persistSettings(toPersisted(next));
            return next;
        });
    },

    setDesktopNotificationsEnabled: (value) => {
        set((state) => {
            const next: AlertsState = {
                ...state,
                desktopNotificationsEnabled: value,
            };
            persistSettings(toPersisted(next));
            return next;
        });
    },

    setSnoozeUntil: (value) => {
        set((state) => {
            const next: AlertsState = {
                ...state,
                snoozeUntilEpochMs: value,
            };
            persistSettings(toPersisted(next));
            return next;
        });
    },

    clearExpiredSnooze: (nowEpochMs = Date.now()) => {
        const state = get();
        if (!state.snoozeUntilEpochMs || state.snoozeUntilEpochMs > nowEpochMs) {
            return;
        }
        set((prev) => {
            const next: AlertsState = {
                ...prev,
                snoozeUntilEpochMs: null,
            };
            persistSettings(toPersisted(next));
            return next;
        });
    },

    setAudioBlocked: (value) => {
        set((state) => ({
            ...state,
            audioBlocked: value,
        }));
    },

    setNotificationBlocked: (value) => {
        set((state) => ({
            ...state,
            notificationBlocked: value,
        }));
    },

    triggerAlert: (alert) => {
        set((state) => {
            const next: AlertsState = {
                ...state,
                lastAlertedSignature: alert.signature,
                lastAlertedAtIso: alert.triggeredAtIso,
                modalAlert: alert,
                bannerAlert: alert,
                modalLockUntilEpochMs: Date.now() + ALERT_LOCK_MS,
            };
            persistSettings(toPersisted(next));
            return next;
        });
    },

    dismissModal: () => {
        set((state) => ({
            ...state,
            modalAlert: null,
            modalLockUntilEpochMs: null,
        }));
    },

    dismissBanner: () => {
        set((state) => ({
            ...state,
            bannerAlert: null,
        }));
    },

    snoozeForMinutes: (minutes) => {
        const until = Date.now() + Math.max(1, minutes) * 60_000;
        set((state) => {
            const next: AlertsState = {
                ...state,
                snoozeUntilEpochMs: until,
                modalAlert: null,
                modalLockUntilEpochMs: null,
            };
            persistSettings(toPersisted(next));
            return next;
        });
    },
}));
