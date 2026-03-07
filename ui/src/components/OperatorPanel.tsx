import React, { useEffect, useState } from 'react';
import {
    BellRing,
    DownloadCloud,
    HardDrive,
    PauseCircle,
    PlayCircle,
    ShieldAlert,
    Volume2,
} from 'lucide-react';
import { getSettings, updateSettings, type SettingsDTO } from '../api/client';
import { buildApiUrl } from '../api/axiosSetup';
import { resumeAlertAudioContext, playAlertSound, stopAlertSound } from '../alerts/alertAudio';
import { useAlertsStore } from '../store/alertsStore';
import { parseApiError } from '../utils/apiError';
import { usePermissions } from '../hooks/usePermissions';
import { disabledByPermissionTooltip } from '../utils/permissionUi';

function formatSnoozeTime(epochMs: number): string {
    return new Date(epochMs).toLocaleTimeString([], {
        hour: '2-digit',
        minute: '2-digit',
    });
}

type ToggleField = 'schedulerEnabled' | 'safeMode';
type SavingField = ToggleField | 'scanIntervalMinutes' | null;

export const OperatorPanel: React.FC = () => {
    const { can } = usePermissions();
    const [settings, setSettings] = useState<SettingsDTO | null>(null);
    const [loading, setLoading] = useState(true);
    const [savingField, setSavingField] = useState<SavingField>(null);
    const [intervalDraft, setIntervalDraft] = useState('');
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
    const [error, setError] = useState<{ errorCode: string; message: string; traceId: string | null } | null>(null);
    const [alertHelper, setAlertHelper] = useState<string | null>(null);
    const canAutoscanToggle = can('scan.autoscan.toggle');
    const autoscanToggleTooltip = disabledByPermissionTooltip('scan.autoscan.toggle', canAutoscanToggle);

    const armed = useAlertsStore((state) => state.armed);
    const volume = useAlertsStore((state) => state.volume);
    const desktopNotificationsEnabled = useAlertsStore((state) => state.desktopNotificationsEnabled);
    const snoozeUntilEpochMs = useAlertsStore((state) => state.snoozeUntilEpochMs);
    const audioBlocked = useAlertsStore((state) => state.audioBlocked);
    const notificationBlocked = useAlertsStore((state) => state.notificationBlocked);

    const setArmed = useAlertsStore((state) => state.setArmed);
    const setVolume = useAlertsStore((state) => state.setVolume);
    const setDesktopNotificationsEnabled = useAlertsStore((state) => state.setDesktopNotificationsEnabled);
    const setAudioBlocked = useAlertsStore((state) => state.setAudioBlocked);
    const setNotificationBlocked = useAlertsStore((state) => state.setNotificationBlocked);
    const setSnoozeUntil = useAlertsStore((state) => state.setSnoozeUntil);
    const clearExpiredSnooze = useAlertsStore((state) => state.clearExpiredSnooze);

    useEffect(() => {
        const fetchSettings = async () => {
            try {
                const s = await getSettings();
                setSettings(s);
                setIntervalDraft(String(s.scanIntervalMinutes));
            } catch (err) {
                const parsed = parseApiError(err);
                setError({ errorCode: parsed.errorCode, message: parsed.message, traceId: parsed.traceId });
            } finally {
                setLoading(false);
            }
        };
        void fetchSettings();
    }, []);

    useEffect(() => {
        clearExpiredSnooze(Date.now());
    }, [clearExpiredSnooze, snoozeUntilEpochMs]);

    const applyError = (err: unknown) => {
        const parsed = parseApiError(err);
        setError({
            errorCode: parsed.errorCode,
            message: parsed.message,
            traceId: parsed.traceId,
        });
        setFieldErrors(parsed.fieldErrors);
    };

    const handleToggle = async (key: ToggleField) => {
        if (!settings || savingField) {
            return;
        }
        if (key === 'schedulerEnabled' && !canAutoscanToggle) {
            return;
        }

        const previous = { ...settings };
        const newValue = !settings[key];
        setSettings({ ...settings, [key]: newValue });
        setSavingField(key);
        setError(null);
        setFieldErrors({});

        try {
            const updated = await updateSettings({ [key]: newValue });
            setSettings(updated);
            setIntervalDraft(String(updated.scanIntervalMinutes));
        } catch (err) {
            setSettings(previous);
            applyError(err);
        } finally {
            setSavingField(null);
        }
    };

    const handleIntervalSave = async () => {
        if (!settings || savingField) {
            return;
        }

        const parsedValue = Number(intervalDraft);
        if (!Number.isFinite(parsedValue) || !Number.isInteger(parsedValue)) {
            setFieldErrors({ scanIntervalMinutes: 'Scan interval must be a whole number.' });
            setError({ errorCode: 'VALIDATION', message: 'Invalid scan interval value.', traceId: null });
            return;
        }

        const previous = { ...settings };
        setSettings({ ...settings, scanIntervalMinutes: parsedValue });
        setSavingField('scanIntervalMinutes');
        setError(null);
        setFieldErrors({});

        try {
            const updated = await updateSettings({ scanIntervalMinutes: parsedValue });
            setSettings(updated);
            setIntervalDraft(String(updated.scanIntervalMinutes));
        } catch (err) {
            setSettings(previous);
            setIntervalDraft(String(previous.scanIntervalMinutes));
            applyError(err);
        } finally {
            setSavingField(null);
        }
    };

    const handleArmToggle = async () => {
        setAlertHelper(null);

        if (armed) {
            setArmed(false);
            setAudioBlocked(false);
            stopAlertSound();
            return;
        }

        const resumed = await resumeAlertAudioContext();
        if (!resumed) {
            setArmed(false);
            setAudioBlocked(true);
            setAlertHelper('Audio blocked — click Arm Alerts and allow audio playback.');
            return;
        }

        setAudioBlocked(false);
        setArmed(true);
    };

    const handleTestSound = async () => {
        setAlertHelper(null);

        const resumed = await resumeAlertAudioContext();
        if (!resumed) {
            setAudioBlocked(true);
            setAlertHelper('Audio blocked — click Arm Alerts first to unlock browser audio.');
            return;
        }

        const played = playAlertSound({ durationMs: 10_000, volume });
        setAudioBlocked(!played);
        if (!played) {
            setAlertHelper('Unable to play alert sound. Re-arm alerts and retry.');
        }
    };

    const handleDesktopNotificationsToggle = async () => {
        setAlertHelper(null);

        if (desktopNotificationsEnabled) {
            setDesktopNotificationsEnabled(false);
            setNotificationBlocked(false);
            return;
        }

        if (typeof window === 'undefined' || !('Notification' in window)) {
            setNotificationBlocked(true);
            setAlertHelper('Desktop notifications are not supported in this browser.');
            return;
        }

        if (Notification.permission === 'granted') {
            setDesktopNotificationsEnabled(true);
            setNotificationBlocked(false);
            return;
        }

        if (Notification.permission === 'denied') {
            setDesktopNotificationsEnabled(false);
            setNotificationBlocked(true);
            setAlertHelper('Desktop notifications are blocked in browser settings.');
            return;
        }

        const permission = await Notification.requestPermission();
        if (permission === 'granted') {
            setDesktopNotificationsEnabled(true);
            setNotificationBlocked(false);
            return;
        }

        setDesktopNotificationsEnabled(false);
        setNotificationBlocked(true);
        setAlertHelper('Desktop notifications were not granted.');
    };

    if (loading) {
        return <div className="text-sm text-gray-500 animate-pulse">Loading Operator Controls...</div>;
    }

    return (
        <div className="bg-gray-900 border border-gray-700 rounded-lg p-6 mt-6 shadow-lg">
            <h3 className="text-lg font-bold text-gray-200 mb-4 flex items-center">
                <HardDrive className="mr-2 text-indigo-400" size={20} />
                Operator Controls & Exports
            </h3>

            {error && (
                <div className="mb-4 rounded border border-rose-600/60 bg-rose-900/35 p-3">
                    <p className="text-sm font-semibold text-rose-100">
                        {error.errorCode}: {error.message}
                    </p>
                    {Object.keys(fieldErrors).length > 0 && (
                        <div className="mt-2 space-y-1">
                            {Object.entries(fieldErrors).map(([key, message]) => (
                                <p key={key} className="text-xs text-rose-200">
                                    {key}: {message}
                                </p>
                            ))}
                        </div>
                    )}
                    {error.traceId && (
                        <div className="mt-2 flex items-center gap-3 text-xs text-rose-200">
                            <span className="font-mono">Trace: {error.traceId}</span>
                            <button
                                onClick={() => {
                                    void navigator.clipboard.writeText(error.traceId ?? '');
                                }}
                                className="rounded border border-rose-400/60 px-2 py-1 hover:bg-rose-800/60"
                            >
                                Copy Trace
                            </button>
                        </div>
                    )}
                </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
                <div className="space-y-4">
                    {settings && (
                        <>
                            <div className="flex items-center justify-between p-3 bg-gray-800 rounded border border-gray-700">
                                <div className="flex items-center">
                                    {settings.schedulerEnabled ? (
                                        <PlayCircle className="text-green-400 mr-3" size={20} />
                                    ) : (
                                        <PauseCircle className="text-gray-500 mr-3" size={20} />
                                    )}
                                    <div>
                                        <p className="text-sm font-semibold text-white">Autoscan Schedule</p>
                                        <p className="text-xs text-gray-400">Run background scans</p>
                                    </div>
                                </div>
                                <button
                                    onClick={() => void handleToggle('schedulerEnabled')}
                                    disabled={savingField !== null || !canAutoscanToggle}
                                    title={autoscanToggleTooltip}
                                    className={`px-3 py-1 rounded text-xs font-bold transition-colors ${
                                        settings.schedulerEnabled
                                            ? 'bg-green-900/50 text-green-400 border border-green-800 hover:bg-green-800/80'
                                            : 'bg-gray-700 text-gray-400 hover:bg-gray-600'
                                    } ${savingField !== null ? 'opacity-60 cursor-not-allowed' : ''}`}
                                >
                                    {savingField === 'schedulerEnabled' ? 'SAVING...' : (settings.schedulerEnabled ? 'ENABLED' : 'DISABLED')}
                                </button>
                            </div>

                            <div className="flex items-center justify-between p-3 bg-gray-800 rounded border border-gray-700">
                                <div className="flex items-center">
                                    <ShieldAlert className={`${settings.safeMode ? 'text-yellow-500' : 'text-gray-500'} mr-3`} size={20} />
                                    <div>
                                        <p className="text-sm font-semibold text-white">Safe Mode</p>
                                        <p className="text-xs text-gray-400">Blocks auto-scanning strictly</p>
                                    </div>
                                </div>
                                <button
                                    onClick={() => void handleToggle('safeMode')}
                                    disabled={savingField !== null}
                                    className={`px-3 py-1 rounded text-xs font-bold transition-colors ${
                                        settings.safeMode
                                            ? 'bg-yellow-900/50 text-yellow-500 border border-yellow-800 hover:bg-yellow-800/80'
                                            : 'bg-gray-700 text-gray-400 hover:bg-gray-600'
                                    } ${savingField !== null ? 'opacity-60 cursor-not-allowed' : ''}`}
                                >
                                    {savingField === 'safeMode' ? 'SAVING...' : (settings.safeMode ? 'ACTIVE' : 'OFF')}
                                </button>
                            </div>

                            <div className="p-3 bg-gray-800 rounded border border-gray-700 space-y-2">
                                <div>
                                    <p className="text-sm font-semibold text-white">Scan Interval</p>
                                    <p className="text-xs text-gray-400">Minutes between scans</p>
                                </div>
                                <div className="flex items-center gap-2">
                                    <input
                                        type="number"
                                        min={1}
                                        step={1}
                                        value={intervalDraft}
                                        onChange={(e) => {
                                            setIntervalDraft(e.target.value);
                                            if (fieldErrors.scanIntervalMinutes) {
                                                const next = { ...fieldErrors };
                                                delete next.scanIntervalMinutes;
                                                setFieldErrors(next);
                                            }
                                        }}
                                        onKeyDown={(e) => {
                                            if (e.key === 'Enter') {
                                                e.preventDefault();
                                                void handleIntervalSave();
                                            }
                                        }}
                                        className="w-24 bg-gray-900 border border-gray-600 text-white rounded p-1 text-sm outline-none focus:border-indigo-500"
                                        disabled={savingField !== null}
                                    />
                                    <button
                                        onClick={() => void handleIntervalSave()}
                                        disabled={savingField !== null || intervalDraft.trim() === '' || Number(intervalDraft) === settings.scanIntervalMinutes}
                                        className="px-3 py-1 rounded text-xs font-bold bg-indigo-700 text-indigo-100 border border-indigo-500 hover:bg-indigo-600 disabled:opacity-60 disabled:cursor-not-allowed"
                                    >
                                        {savingField === 'scanIntervalMinutes' ? 'SAVING...' : 'SAVE'}
                                    </button>
                                </div>
                                {fieldErrors.scanIntervalMinutes && (
                                    <p className="text-xs text-rose-300">{fieldErrors.scanIntervalMinutes}</p>
                                )}
                            </div>
                        </>
                    )}

                    <div className="rounded border border-cyan-700/50 bg-cyan-900/20 p-4 space-y-4">
                        <div className="flex items-center justify-between gap-4">
                            <div className="flex items-center">
                                <BellRing className="text-cyan-300 mr-3" size={20} />
                                <div>
                                    <p className="text-sm font-semibold text-white">Alerts</p>
                                    <p className="text-xs text-gray-300">Arm sound + placeable trade popup</p>
                                </div>
                            </div>
                            <button
                                onClick={() => void handleArmToggle()}
                                className={`px-3 py-1 rounded text-xs font-bold transition-colors ${
                                    armed
                                        ? 'bg-green-900/50 text-green-400 border border-green-700 hover:bg-green-800/70'
                                        : 'bg-gray-700 text-gray-300 border border-gray-600 hover:bg-gray-600'
                                }`}
                            >
                                {armed ? 'ARMED' : 'OFF'}
                            </button>
                        </div>

                        <div className="flex flex-wrap gap-2">
                            <button
                                onClick={() => void handleTestSound()}
                                className="text-xs font-bold text-cyan-200 hover:text-cyan-100 px-3 py-1.5 bg-cyan-800/40 rounded border border-cyan-700/70"
                            >
                                Test Sound (10s)
                            </button>
                            <button
                                onClick={stopAlertSound}
                                className="text-xs font-bold text-rose-200 hover:text-rose-100 px-3 py-1.5 bg-rose-800/30 rounded border border-rose-700/60"
                            >
                                Stop Sound
                            </button>
                        </div>

                        <div>
                            <div className="flex justify-between text-xs text-gray-300 mb-1">
                                <span className="inline-flex items-center gap-1">
                                    <Volume2 size={13} /> Volume
                                </span>
                                <span>{volume.toFixed(2)}</span>
                            </div>
                            <input
                                type="range"
                                min={0}
                                max={1}
                                step={0.01}
                                value={volume}
                                onChange={(e) => setVolume(Number(e.target.value))}
                                className="w-full accent-cyan-400"
                            />
                        </div>

                        <div className="flex items-center justify-between p-2 bg-gray-900/40 rounded border border-gray-700/60">
                            <div>
                                <p className="text-xs font-semibold text-white">Desktop Notifications</p>
                                <p className="text-[11px] text-gray-400">Optional browser pop-up on trigger</p>
                            </div>
                            <button
                                onClick={() => void handleDesktopNotificationsToggle()}
                                className={`px-3 py-1 rounded text-[11px] font-bold ${
                                    desktopNotificationsEnabled
                                        ? 'bg-green-900/50 text-green-400 border border-green-700'
                                        : 'bg-gray-700 text-gray-300 border border-gray-600'
                                }`}
                            >
                                {desktopNotificationsEnabled ? 'ENABLED' : 'DISABLED'}
                            </button>
                        </div>

                        {snoozeUntilEpochMs && snoozeUntilEpochMs > Date.now() && (
                            <div className="flex items-center justify-between rounded border border-yellow-700/50 bg-yellow-900/25 px-3 py-2">
                                <p className="text-xs text-yellow-200">
                                    Alerts snoozed until {formatSnoozeTime(snoozeUntilEpochMs)}
                                </p>
                                <button
                                    onClick={() => setSnoozeUntil(null)}
                                    className="text-[11px] font-bold text-yellow-200 hover:text-yellow-100"
                                >
                                    Clear
                                </button>
                            </div>
                        )}

                        {(audioBlocked || notificationBlocked || alertHelper) && (
                            <div className="rounded border border-amber-700/60 bg-amber-900/25 px-3 py-2 text-xs text-amber-100">
                                {alertHelper || 'Audio blocked — click Arm Alerts.'}
                            </div>
                        )}
                    </div>
                </div>

                <div className="space-y-4">
                    <p className="text-sm text-gray-400 mb-2">
                        Export your localized data straight from the Postgres instance.
                    </p>

                    <a
                        href={buildApiUrl('/api/v1/export/journal')}
                        download
                        className="flex items-center justify-between p-4 bg-gray-800 hover:bg-gray-700 rounded border border-gray-600 transition-colors group cursor-pointer"
                    >
                        <div>
                            <p className="text-sm font-bold text-gray-200 group-hover:text-white">Trade Journal (.csv)</p>
                            <p className="text-xs text-gray-500 mt-1">Full recommendation and feedback history logs</p>
                        </div>
                        <DownloadCloud className="text-gray-400 group-hover:text-green-400" size={20} />
                    </a>

                    <a
                        href={buildApiUrl('/api/v1/export/analytics')}
                        download
                        className="flex items-center justify-between p-4 bg-gray-800 hover:bg-gray-700 rounded border border-gray-600 transition-colors group cursor-pointer"
                    >
                        <div>
                            <p className="text-sm font-bold text-gray-200 group-hover:text-white">Active Rules & Summary (.json)</p>
                            <p className="text-xs text-gray-500 mt-1">Current tuned parameters and performance stats</p>
                        </div>
                        <DownloadCloud className="text-gray-400 group-hover:text-green-400" size={20} />
                    </a>
                </div>
            </div>
        </div>
    );
};
