package io.paynexa.platform;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token bucket per key. <b>Per instance only</b>: with N replicas the effective limit is up to N times the
 * configured rate. Distributed limiting needs shared state (e.g. Redis) and is deliberately not claimed here.
 * Memory is bounded: idle buckets are evicted and the key count is capped (fail-closed to a shared overflow bucket).
 */
public final class TokenBucketRateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private static final String OVERFLOW_KEY = "\u0000overflow";

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;
        volatile long lastUsedNanos;
    }

    private final int capacity;
    private final double refillPerSecond;
    private final int maxKeys;
    private final long idleEvictNanos;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private volatile long lastSweepNanos;

    public TokenBucketRateLimiter(int capacity, double refillPerSecond, int maxKeys, java.time.Duration idleEvict, Clock clock) {
        if (capacity < 1 || refillPerSecond <= 0 || maxKeys < 1) {
            throw new IllegalArgumentException("capacity, refill rate and maxKeys must be positive");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.maxKeys = maxKeys;
        this.idleEvictNanos = idleEvict.toNanos();
        this.clock = clock;
        this.lastSweepNanos = nanos();
    }

    private long nanos() {
        var i = clock.instant();
        return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    }

    public Decision tryAcquire(String key) {
        long now = nanos();
        sweepIfDue(now);
        var effectiveKey = (buckets.size() >= maxKeys && !buckets.containsKey(key)) ? OVERFLOW_KEY : key;
        var bucket = buckets.computeIfAbsent(effectiveKey, k -> {
            var b = new Bucket();
            b.tokens = capacity;
            b.lastRefillNanos = now;
            return b;
        });
        synchronized (bucket) {
            double elapsedSeconds = Math.max(0, (now - bucket.lastRefillNanos) / 1e9);
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSecond);
            bucket.lastRefillNanos = now;
            bucket.lastUsedNanos = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return new Decision(true, 0);
            }
            long wait = (long) Math.ceil((1.0 - bucket.tokens) / refillPerSecond);
            return new Decision(false, Math.max(1, wait));
        }
    }

    private void sweepIfDue(long now) {
        if (now - lastSweepNanos < idleEvictNanos / 2) {
            return;
        }
        lastSweepNanos = now;
        buckets.entrySet().removeIf(e -> now - e.getValue().lastUsedNanos > idleEvictNanos);
    }

    int size() {
        return buckets.size();
    }
}
