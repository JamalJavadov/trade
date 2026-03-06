import { useState, useEffect } from 'react';

export function usePolling<T>(
    fetchFn: () => Promise<T>,
    intervalMs: number,
    isPaused: boolean = false
) {
    const [data, setData] = useState<T | null>(null);
    const [error, setError] = useState<Error | null>(null);
    const [loading, setLoading] = useState(true);

    const fetchData = async () => {
        try {
            const result = await fetchFn();
            setData(result);
            setError(null);
        } catch (err) {
            setError(err instanceof Error ? err : new Error('Unknown error'));
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData(); // initial fetch

        if (isPaused) return;

        const intervalId = setInterval(fetchData, intervalMs);
        return () => clearInterval(intervalId);
    }, [intervalMs, isPaused]);

    return { data, error, loading, refetch: fetchData };
}
