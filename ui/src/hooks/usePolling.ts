import { useCallback, useEffect, useRef, useState } from 'react';

export function usePolling<T>(
    fetchFn: () => Promise<T>,
    intervalMs: number,
    isPaused: boolean = false
) {
    const [data, setData] = useState<T | null>(null);
    const [error, setError] = useState<Error | null>(null);
    const [loading, setLoading] = useState(true);
    const inFlightRef = useRef<Promise<void> | null>(null);

    const fetchData = useCallback(async () => {
        if (inFlightRef.current) {
            return inFlightRef.current;
        }

        const request = (async () => {
            try {
                const result = await fetchFn();
                setData(result);
                setError(null);
            } catch (err) {
                setError(err instanceof Error ? err : new Error('Unknown error'));
            } finally {
                setLoading(false);
                inFlightRef.current = null;
            }
        })();

        inFlightRef.current = request;
        return request;
    }, [fetchFn]);

    useEffect(() => {
        void fetchData();

        if (isPaused) return;

        const intervalId = setInterval(() => {
            void fetchData();
        }, intervalMs);
        return () => clearInterval(intervalId);
    }, [fetchData, intervalMs, isPaused]);

    return { data, error, loading, refetch: fetchData };
}
