package io.paynexa.platform;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketRateLimiterTest {

    static final class FakeClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @Test
    void allowsBurstUpToCapacityThenRejectsWithRetryAfter() {
        var clock = new FakeClock();
        var limiter = new TokenBucketRateLimiter(3, 1.0, 100, Duration.ofMinutes(5), clock);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        var rejected = limiter.tryAcquire("a");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsOverTimeButNeverAboveCapacity() {
        var clock = new FakeClock();
        var limiter = new TokenBucketRateLimiter(2, 1.0, 100, Duration.ofMinutes(5), clock);
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        clock.advance(Duration.ofHours(1));                   // long idle: capped at capacity (2), not 3600
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void keysAreIndependent() {
        var limiter = new TokenBucketRateLimiter(1, 0.1, 100, Duration.ofMinutes(5), new FakeClock());
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void memoryIsBoundedAndOverflowFailsClosedToASharedBucket() {
        var limiter = new TokenBucketRateLimiter(1, 0.001, 3, Duration.ofHours(1), new FakeClock());
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("k" + i).allowed()).isTrue();
        }
        // key space is full: new keys share one overflow bucket instead of growing the map
        assertThat(limiter.tryAcquire("new1").allowed()).isTrue();
        assertThat(limiter.tryAcquire("new2").allowed()).isFalse();
        assertThat(limiter.size()).isLessThanOrEqualTo(4);
    }

    @Test
    void idleBucketsAreEvicted() {
        var clock = new FakeClock();
        var limiter = new TokenBucketRateLimiter(1, 1.0, 100, Duration.ofMinutes(1), clock);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("k" + i);
        }
        clock.advance(Duration.ofMinutes(5));
        limiter.tryAcquire("fresh");
        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    void concurrentCallersNeverGetMoreThanCapacity() throws Exception {
        var limiter = new TokenBucketRateLimiter(50, 0.0001, 100, Duration.ofMinutes(5), Clock.systemUTC());
        var allowed = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        var go = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < 400; i++) {
            pool.submit(() -> { go.await(); if (limiter.tryAcquire("shared").allowed()) allowed.incrementAndGet(); return null; });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(allowed.get()).isEqualTo(50);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, 1, 1, Duration.ofSeconds(1), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
