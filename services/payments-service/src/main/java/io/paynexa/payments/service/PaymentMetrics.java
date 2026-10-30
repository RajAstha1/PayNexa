package io.paynexa.payments.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.paynexa.payments.domain.PaymentKind;
import io.paynexa.payments.domain.PaymentStatus;
import org.springframework.stereotype.Component;

@Component
public class PaymentMetrics {

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void created(PaymentKind kind, PaymentStatus outcome) {
        registry.counter("paynexa.payments.created", "kind", kind.name(), "outcome", outcome.name()).increment();
    }

    public void transitioned(PaymentKind kind, PaymentStatus to) {
        registry.counter("paynexa.payments.transitions", "kind", kind.name(), "to", to.name()).increment();
    }

    public void replayed() {
        registry.counter("paynexa.payments.idempotent_replays").increment();
    }

    public void transientRetry() {
        registry.counter("paynexa.payments.transient_db_retries").increment();
    }
}
