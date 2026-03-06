export function precisionFromStep(step: string | undefined): number {
    if (!step) return 8;
    const s = step.toString();
    const dot = s.indexOf('.');
    if (dot === -1) return 0;
    return s.length - dot - 1;
}

export function formatPrice(
    value: number | string | null | undefined,
    tickSize?: string
): string {
    if (value === null || value === undefined) return '—';
    const num = typeof value === 'string' ? parseFloat(value) : value;
    if (isNaN(num)) return '—';
    const dp = precisionFromStep(tickSize);
    return num.toFixed(dp);
}

export function formatQty(
    value: number | string | null | undefined,
    stepSize?: string
): string {
    if (value === null || value === undefined) return '—';
    const num = typeof value === 'string' ? parseFloat(value) : value;
    if (isNaN(num)) return '—';
    const dp = precisionFromStep(stepSize);
    return num.toFixed(dp);
}

export function calcNotional(
    qty: number | string | null | undefined,
    price: number | string | null | undefined
): string | null {
    if (qty === null || qty === undefined || price === null || price === undefined) return null;
    const q = typeof qty === 'string' ? parseFloat(qty) : qty;
    const p = typeof price === 'string' ? parseFloat(price) : price;
    if (isNaN(q) || isNaN(p) || p === 0) return null;
    return (q * p).toFixed(2);
}
