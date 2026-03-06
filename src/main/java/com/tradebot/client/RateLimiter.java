package com.tradebot.client;

import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class RateLimiter {
    private final long capacity = 1000;
    private final long refillTokensExhaust = 1200; // Let's say 1200 per minute
    private final long refillIntervalMillis = 60000;
    private long availableTokens = capacity;
    private long lastRefillTimestamp = System.currentTimeMillis();

    public synchronized void consume(int tokens) {
        refill();
        while (availableTokens < tokens) {
            try {
                long waitTime = refillIntervalMillis - (System.currentTimeMillis() - lastRefillTimestamp);
                if (waitTime > 0) {
                    Thread.sleep(waitTime);
                }
                refill();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("RateLimiter interrupted", e);
            }
        }
        availableTokens -= tokens;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsedTime = now - lastRefillTimestamp;
        if (elapsedTime >= refillIntervalMillis) {
            long tokensToAdd = (elapsedTime / refillIntervalMillis) * refillTokensExhaust;
            availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
            lastRefillTimestamp = now;
        }
    }
}
