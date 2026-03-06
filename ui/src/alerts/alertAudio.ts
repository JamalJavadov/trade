const DEFAULT_DURATION_MS = 10_000;

let audioContextRef: AudioContext | null = null;
let gainNodeRef: GainNode | null = null;
let oscPrimaryRef: OscillatorNode | null = null;
let oscSecondaryRef: OscillatorNode | null = null;
let sirenIntervalRef: number | null = null;
let stopTimeoutRef: number | null = null;
let soundPlaying = false;

function supportsWebAudio(): boolean {
    return typeof window !== 'undefined' && (!!window.AudioContext || !!(window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext);
}

function getAudioContext(): AudioContext | null {
    if (!supportsWebAudio()) {
        return null;
    }

    if (audioContextRef) {
        return audioContextRef;
    }

    const Ctx = window.AudioContext || (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) {
        return null;
    }

    audioContextRef = new Ctx();
    return audioContextRef;
}

function disconnectNodes() {
    if (sirenIntervalRef != null) {
        window.clearInterval(sirenIntervalRef);
        sirenIntervalRef = null;
    }

    if (stopTimeoutRef != null) {
        window.clearTimeout(stopTimeoutRef);
        stopTimeoutRef = null;
    }

    if (oscPrimaryRef) {
        try {
            oscPrimaryRef.stop();
        } catch {
            // ignore if already stopped
        }
        oscPrimaryRef.disconnect();
        oscPrimaryRef = null;
    }

    if (oscSecondaryRef) {
        try {
            oscSecondaryRef.stop();
        } catch {
            // ignore if already stopped
        }
        oscSecondaryRef.disconnect();
        oscSecondaryRef = null;
    }

    if (gainNodeRef) {
        gainNodeRef.disconnect();
        gainNodeRef = null;
    }

    soundPlaying = false;
}

export async function resumeAlertAudioContext(): Promise<boolean> {
    const ctx = getAudioContext();
    if (!ctx) {
        return false;
    }

    try {
        if (ctx.state === 'suspended') {
            await ctx.resume();
        }
        return ctx.state === 'running';
    } catch {
        return false;
    }
}

export function isAlertSoundPlaying(): boolean {
    return soundPlaying;
}

export function stopAlertSound(): void {
    disconnectNodes();
}

export function playAlertSound(options?: { durationMs?: number; volume?: number }): boolean {
    const durationMs = options?.durationMs ?? DEFAULT_DURATION_MS;
    const targetVolume = Math.min(1, Math.max(0, options?.volume ?? 0.9));

    const ctx = getAudioContext();
    if (!ctx || ctx.state !== 'running') {
        return false;
    }

    disconnectNodes();

    gainNodeRef = ctx.createGain();
    const now = ctx.currentTime;
    gainNodeRef.gain.setValueAtTime(0.0001, now);
    gainNodeRef.gain.exponentialRampToValueAtTime(Math.max(0.05, targetVolume), now + 0.08);
    gainNodeRef.connect(ctx.destination);

    oscPrimaryRef = ctx.createOscillator();
    oscSecondaryRef = ctx.createOscillator();
    oscPrimaryRef.type = 'square';
    oscSecondaryRef.type = 'sawtooth';
    oscPrimaryRef.frequency.setValueAtTime(880, now);
    oscSecondaryRef.frequency.setValueAtTime(660, now);
    oscPrimaryRef.connect(gainNodeRef);
    oscSecondaryRef.connect(gainNodeRef);
    oscPrimaryRef.start();
    oscSecondaryRef.start();
    soundPlaying = true;

    let highTone = false;
    sirenIntervalRef = window.setInterval(() => {
        if (!oscPrimaryRef || !oscSecondaryRef || !audioContextRef) {
            return;
        }

        const t = audioContextRef.currentTime;
        highTone = !highTone;
        const primaryFreq = highTone ? 880 : 660;
        const secondaryFreq = highTone ? 1320 : 990;

        oscPrimaryRef.frequency.cancelScheduledValues(t);
        oscSecondaryRef.frequency.cancelScheduledValues(t);
        oscPrimaryRef.frequency.setValueAtTime(primaryFreq, t);
        oscSecondaryRef.frequency.setValueAtTime(secondaryFreq, t);
    }, 300);

    stopTimeoutRef = window.setTimeout(() => {
        stopAlertSound();
    }, Math.max(500, durationMs));

    return true;
}
