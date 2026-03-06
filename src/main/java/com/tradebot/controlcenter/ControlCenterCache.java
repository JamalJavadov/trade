package com.tradebot.controlcenter;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.Supplier;

@Component
public class ControlCenterCache {

    private static final long DEFAULT_TTL_MS = 5_000L;

    private final Object lock = new Object();
    private volatile CacheValue cached;

    public CachedState get(Supplier<CachedState> loader) {
        long now = System.currentTimeMillis();
        CacheValue value = cached;
        if (value != null && value.expiresAtMs() > now) {
            return value.state();
        }

        synchronized (lock) {
            value = cached;
            now = System.currentTimeMillis();
            if (value != null && value.expiresAtMs() > now) {
                return value.state();
            }
            CachedState state = loader.get();
            cached = new CacheValue(state, now + DEFAULT_TTL_MS);
            return state;
        }
    }

    public void invalidate() {
        synchronized (lock) {
            cached = null;
        }
    }

    private record CacheValue(CachedState state, long expiresAtMs) {
    }

    public record CachedState(ControlCenterConfig config, int version, Instant updatedAt, String updatedBy) {
    }
}
