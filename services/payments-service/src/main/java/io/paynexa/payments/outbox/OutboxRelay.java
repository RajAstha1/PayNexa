package io.paynexa.payments.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.paynexa.contracts.PaymentEvent;
import io.paynexa.payments.config.PaymentsProperties;
import io.paynexa.payments.persistence.OutboxRepository;
import io.paynexa.platform.Correlation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;

/**
 * Publishes committed outbox rows to the broker.
 *
 * <p>Guarantees: <b>at-least-once</b>. An event is marked PUBLISHED only after the broker acknowledged it. If the
 * process dies between the acknowledgement and the database commit, the event is published again, so consumers must
 * deduplicate on {@code eventId}. Order: one active publisher (advisory lock) sends events in outbox-id order; a
 * failing event stops the pass so later events of the same payment cannot overtake it. An event that exhausts its
 * attempts becomes DEAD and no longer blocks the queue; consumers detect the gap through {@code sequence}.
 */
@Component
public final class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final TransactionTemplate tx;
    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final PaymentsProperties.Outbox config;
    private final Clock clock;
    private final MeterRegistry meters;

    public OutboxRelay(TransactionTemplate tx, OutboxRepository outbox, EventPublisher publisher,
                       PaymentsProperties props, Clock clock, MeterRegistry meters) {
        this.tx = tx;
        this.outbox = outbox;
        this.publisher = publisher;
        this.config = props.outbox();
        this.clock = clock;
        this.meters = meters;
    }

    /** One relay pass. Returns the number of events acknowledged by the broker. */
    public int runOnce() {
        Integer published = tx.execute(status -> {
            if (!outbox.tryRelayLock()) {
                return 0; // another instance is the active publisher
            }
            int sent = 0;
            for (var row : outbox.due(config.batchSize())) {
                var headers = new HashMap<String, String>();
                headers.put("event-id", row.eventId().toString());
                headers.put("event-type", row.eventType());
                headers.put("sequence", Integer.toString(row.sequence()));
                if (row.correlationId() != null) {
                    headers.put(Correlation.KAFKA_HEADER, row.correlationId());
                }
                try {
                    publisher.publish(PaymentEvent.TOPIC, row.aggregateId().toString(), row.payload(), headers);
                    outbox.markPublished(row.id());
                    sent++;
                    meters.counter("paynexa.outbox.published").increment();
                } catch (Exception e) {
                    int attempts = row.attempts() + 1;
                    boolean dead = attempts >= config.maxAttempts();
                    outbox.markFailed(row.id(), attempts, clock.instant().plus(backoff(attempts)),
                            e.getClass().getSimpleName() + ": " + e.getMessage(), dead);
                    meters.counter("paynexa.outbox.failures").increment();
                    log.warn("outbox event {} type={} failed attempt {}/{}{}", row.id(), row.eventType(), attempts,
                            config.maxAttempts(), dead ? " -> DEAD" : "", e);
                    if (!dead) {
                        break; // preserve order: do not let later events overtake this one
                    }
                }
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    Duration backoff(int attempts) {
        long millis = config.baseBackoff().toMillis() * (1L << Math.min(attempts - 1, 20));
        return Duration.ofMillis(Math.min(millis, config.maxBackoff().toMillis()));
    }
}
