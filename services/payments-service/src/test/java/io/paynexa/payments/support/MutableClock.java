package io.paynexa.payments.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    public MutableClock() {
        this.now = new AtomicReference<>(Instant.now());
    }

    public void advance(Duration d) {
        now.updateAndGet(i -> i.plus(d));
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
}
