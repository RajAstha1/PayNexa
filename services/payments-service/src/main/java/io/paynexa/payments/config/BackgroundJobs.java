package io.paynexa.payments.config;

import io.paynexa.payments.persistence.IdempotencyRepository;
import io.paynexa.payments.recon.ReconciliationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "paynexa.payments.reconciliation.scheduled", havingValue = "true", matchIfMissing = true)
class BackgroundJobs {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobs.class);

    private final ReconciliationService reconciliation;
    private final IdempotencyRepository idempotency;

    BackgroundJobs(ReconciliationService reconciliation, IdempotencyRepository idempotency) {
        this.reconciliation = reconciliation;
        this.idempotency = idempotency;
    }

    @PostConstruct
    void bind() {
        reconciliation.bindMetrics();
    }

    @Scheduled(fixedDelayString = "${paynexa.payments.reconciliation.interval:5m}", initialDelayString = "1m")
    void reconcile() {
        try {
            var report = reconciliation.run("SCHEDULED");
            log.info("scheduled reconciliation finished: {} finding(s)", report.findings().size());
        } catch (RuntimeException e) {
            log.warn("scheduled reconciliation failed: {}", e.toString());
        }
    }

    @Scheduled(cron = "0 17 * * * *")
    void purgeExpiredIdempotencyKeys() {
        try {
            int n = idempotency.deleteExpired();
            if (n > 0) {
                log.info("purged {} expired idempotency key(s)", n);
            }
        } catch (RuntimeException e) {
            log.warn("idempotency purge failed: {}", e.toString());
        }
    }
}
