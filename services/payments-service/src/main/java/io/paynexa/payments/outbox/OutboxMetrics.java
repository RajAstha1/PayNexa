package io.paynexa.payments.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.paynexa.payments.persistence.OutboxRepository;
import org.springframework.stereotype.Component;

/** Backlog and dead-event gauges: the signals an on-call engineer alerts on (see docs/ops/RUNBOOKS.md). */
@Component
class OutboxMetrics implements MeterBinder {

    private final OutboxRepository outbox;

    OutboxMetrics(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("paynexa.outbox.pending", outbox, o -> o.count("PENDING")).register(registry);
        Gauge.builder("paynexa.outbox.dead", outbox, o -> o.count("DEAD")).register(registry);
    }
}
