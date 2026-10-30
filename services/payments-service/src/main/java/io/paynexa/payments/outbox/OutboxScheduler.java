package io.paynexa.payments.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "paynexa.payments.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);
    private final OutboxRelay relay;

    OutboxScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${paynexa.payments.outbox.poll-interval:200ms}")
    void tick() {
        try {
            relay.runOnce();
        } catch (RuntimeException e) {
            // database unavailable etc.: nothing was acknowledged as published; next tick retries
            log.warn("outbox relay pass failed: {}", e.toString());
        }
    }
}
