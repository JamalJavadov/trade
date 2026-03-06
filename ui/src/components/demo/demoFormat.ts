export function toNumber(value: number | string | null | undefined): number | null {
    if (value === null || value === undefined) {
        return null;
    }

    const parsed = typeof value === 'number' ? value : Number(value);
    return Number.isFinite(parsed) ? parsed : null;
}

export function formatMoney(value: number | string | null | undefined): string {
    const num = toNumber(value);
    if (num === null) {
        return 'N/A';
    }

    return `$${num.toLocaleString(undefined, {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
    })}`;
}

export function formatCompactMoney(value: number | string | null | undefined): string {
    const num = toNumber(value);
    if (num === null) {
        return 'N/A';
    }

    return `${num.toLocaleString(undefined, {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
    })}`;
}

export function formatSignedMoney(value: number | string | null | undefined): string {
    const num = toNumber(value);
    if (num === null) {
        return 'N/A';
    }

    const sign = num > 0 ? '+' : '';
    return `${sign}${formatCompactMoney(num)} USDT`;
}

export function formatSignedR(value: number | string | null | undefined): string {
    const num = toNumber(value);
    if (num === null) {
        return 'N/A';
    }

    const sign = num > 0 ? '+' : '';
    return `${sign}${num.toFixed(2)}R`;
}

export function formatPercentFromRatio(value: number | string | null | undefined): string {
    const num = toNumber(value);
    if (num === null) {
        return 'N/A';
    }

    return `${(num * 100).toFixed(2)}%`;
}

export function formatPercentDirect(value: number | string | null | undefined): string {
    const num = toNumber(value);
    if (num === null) {
        return 'N/A';
    }

    return `${num.toFixed(2)}%`;
}

export function formatDecimal(value: number | string | null | undefined, digits = 2): string {
    const num = toNumber(value);
    if (num === null) {
        return 'N/A';
    }

    return num.toFixed(digits);
}

export function formatDateTime(value: string | null | undefined): string {
    if (!value) {
        return 'N/A';
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return 'N/A';
    }

    return date.toLocaleString();
}

export function formatDurationMinutes(minutes: number | string | null | undefined): string {
    const num = toNumber(minutes);
    if (num === null) {
        return 'N/A';
    }

    if (num < 60) {
        return `${Math.round(num)}m`;
    }

    const hours = Math.floor(num / 60);
    const rem = Math.round(num % 60);
    return `${hours}h ${rem}m`;
}

export function formatTimeInTrade(openedAt: string | null | undefined, closedAt?: string | null): string {
    if (!openedAt) {
        return 'N/A';
    }

    const start = new Date(openedAt).getTime();
    if (Number.isNaN(start)) {
        return 'N/A';
    }

    const end = closedAt ? new Date(closedAt).getTime() : Date.now();
    if (Number.isNaN(end) || end < start) {
        return 'N/A';
    }

    const diffMs = end - start;
    const minutes = Math.floor(diffMs / 60000);

    if (minutes < 60) {
        return `${minutes}m`;
    }

    const hours = Math.floor(minutes / 60);
    const remMinutes = minutes % 60;

    return `${hours}h ${remMinutes}m`;
}
