package com.hytalefinder.hyquery;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Response cache for HyQuery to reduce CPU usage under load.
 *
 * Caches basic and full responses separately with configurable TTL.
 * Thread-safe for concurrent access from Netty event loops.
 */
public class HyQueryCache {

    private final HyQueryPlugin plugin;
    private final long ttlNanos;

    private volatile CachedResponse basicResponse;
    private volatile CachedResponse fullResponse;

    /**
     * Create a new response cache.
     *
     * @param plugin     The plugin instance for building responses
     * @param ttlSeconds Cache TTL in seconds
     */
    public HyQueryCache(HyQueryPlugin plugin, int ttlSeconds) {
        this.plugin = plugin;
        this.ttlNanos = ttlSeconds * 1_000_000_000L;
    }

    /**
     * Get a cached basic response, rebuilding if expired.
     *
     * @return A retained ByteBuf copy of the cached response
     */
    public ByteBuf getBasicResponse() {
        CachedResponse cached = basicResponse;
        long now = System.nanoTime();

        if (cached == null || now - cached.createdAt > ttlNanos) {
            synchronized (this) {
                cached = basicResponse;
                if (cached == null || now - cached.createdAt > ttlNanos) {
                    ByteBuf fresh = HyQueryProtocol.buildBasicResponse(plugin);
                    byte[] bytes = new byte[fresh.readableBytes()];
                    fresh.readBytes(bytes);
                    fresh.release();
                    basicResponse = cached = new CachedResponse(bytes, now);
                }
            }
        }

        return Unpooled.wrappedBuffer(cached.data);
    }

    /**
     * Get a cached full response, rebuilding if expired.
     *
     * @return A retained ByteBuf copy of the cached response
     */
    public ByteBuf getFullResponse() {
        CachedResponse cached = fullResponse;
        long now = System.nanoTime();

        if (cached == null || now - cached.createdAt > ttlNanos) {
            synchronized (this) {
                cached = fullResponse;
                if (cached == null || now - cached.createdAt > ttlNanos) {
                    ByteBuf fresh = HyQueryProtocol.buildFullResponse(plugin);
                    byte[] bytes = new byte[fresh.readableBytes()];
                    fresh.readBytes(bytes);
                    fresh.release();
                    fullResponse = cached = new CachedResponse(bytes, now);
                }
            }
        }

        return Unpooled.wrappedBuffer(cached.data);
    }

    /**
     * Invalidate all cached responses.
     * Call this if server state changes significantly.
     */
    public void invalidate() {
        basicResponse = null;
        fullResponse = null;
    }

    private static class CachedResponse {
        final byte[] data;
        final long createdAt;

        CachedResponse(byte[] data, long createdAt) {
            this.data = data;
            this.createdAt = createdAt;
        }
    }
}
