package io.paynexa.payments.rail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "paynexa.payments.rail.worker-enabled", havingValue = "true", matchIfMissing = true)
class PayoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(PayoutScheduler.class);
    private final PayoutWorker worker;

    PayoutScheduler(PayoutWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${paynexa.payments.rail.poll-interval:500ms}")
    void tick() {
        try {
            worker.runOnce();
        } catch (RuntimeException e) {
            log.warn("payout worker pass failed: {}", e.toString());
        }
    }
}
