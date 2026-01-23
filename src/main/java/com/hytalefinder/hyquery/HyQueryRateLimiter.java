package com.hytalefinder.hyquery;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP token bucket rate limiter for HyQuery requests.
 *
 * Each IP address gets a bucket that refills at a configured rate.
 * Requests are allowed if tokens are available, denied otherwise.
 * Old entries are cleaned up periodically to prevent memory leaks.
 */
public class HyQueryRateLimiter {

    private final Map<InetAddress, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final int maxTokens;
    private final int refillPerSecond;
    private final long cleanupIntervalNanos;
    private long lastCleanup;

    /**
     * Create a new rate limiter.
     *
     * @param maxTokens       Maximum tokens per bucket (burst capacity)
     * @param refillPerSecond How many tokens to add per second
     */
    public HyQueryRateLimiter(int maxTokens, int refillPerSecond) {
        this.maxTokens = maxTokens;
        this.refillPerSecond = refillPerSecond;
        this.cleanupIntervalNanos = 60_000_000_000L; // 60 seconds
        this.lastCleanup = System.nanoTime();
    }

    /**
     * Try to consume a token for the given address.
     *
     * @param address The source IP address
     * @return true if request is allowed, false if rate limited
     */
    public boolean tryAcquire(InetAddress address) {
        cleanupIfNeeded();

        TokenBucket bucket = buckets.computeIfAbsent(address,
            k -> new TokenBucket(maxTokens, refillPerSecond));

        return bucket.tryConsume();
    }

    /**
     * Periodically remove stale buckets to prevent memory leaks.
     */
    private void cleanupIfNeeded() {
        long now = System.nanoTime();
        if (now - lastCleanup > cleanupIntervalNanos) {
            lastCleanup = now;
            long staleThreshold = now - cleanupIntervalNanos;
            buckets.entrySet().removeIf(entry ->
                entry.getValue().getLastAccessNanos() < staleThreshold);
        }
    }

    /**
     * Token bucket implementation for a single IP.
     */
    private static class TokenBucket {
        private final int maxTokens;
        private final double refillPerNano;
        private double tokens;
        private long lastRefillNanos;
        private long lastAccessNanos;

        TokenBucket(int maxTokens, int refillPerSecond) {
            this.maxTokens = maxTokens;
            this.refillPerNano = refillPerSecond / 1_000_000_000.0;
            this.tokens = maxTokens;
            this.lastRefillNanos = System.nanoTime();
            this.lastAccessNanos = this.lastRefillNanos;
        }

        synchronized boolean tryConsume() {
            refill();
            lastAccessNanos = System.nanoTime();

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            double newTokens = elapsed * refillPerNano;
            tokens = Math.min(maxTokens, tokens + newTokens);
            lastRefillNanos = now;
        }

        long getLastAccessNanos() {
            return lastAccessNanos;
        }
    }
}
