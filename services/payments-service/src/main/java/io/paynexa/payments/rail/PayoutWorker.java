package io.paynexa.payments.rail;

import io.micrometer.core.instrument.MeterRegistry;
import io.paynexa.payments.config.PaymentsProperties;
import io.paynexa.payments.domain.Payment;
import io.paynexa.payments.persistence.PaymentRepository;
import io.paynexa.payments.service.LifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Drives SUBMITTED payouts to a final state.
 *
 * <p>Transaction discipline: a short transaction claims due payouts (SKIP LOCKED) and pushes {@code next_attempt_at}
 * forward as a lease; the rail is then called with <b>no transaction open</b>; the outcome is applied in a separate
 * transaction by {@link LifecycleService}. A crash anywhere in between leaves the payout SUBMITTED; the lease expires and
 * it is retried. The rail call is idempotent by payment id, so retries never pay twice.
 *
 * <p>An unreachable rail never settles or fails a payout: it stays SUBMITTED, and reconciliation reports it once it is
 * older than {@code stuckAfter}. After {@code maxAttempts} the worker keeps retrying at the maximum back-off and raises
 * a metric; deciding to cancel is a human/ops decision, not an automatic one.
 */
@Component
public class PayoutWorker {

    private static final Logger log = LoggerFactory.getLogger(PayoutWorker.class);

    private final TransactionTemplate tx;
    private final PaymentRepository payments;
    private final LifecycleService lifecycle;
    private final RailClient rail;
    private final PaymentsProperties.Rail config;
    private final Clock clock;
    private final MeterRegistry meters;

    public PayoutWorker(TransactionTemplate tx, PaymentRepository payments, LifecycleService lifecycle, RailClient rail,
                        PaymentsProperties props, Clock clock, MeterRegistry meters) {
        this.tx = tx;
        this.payments = payments;
        this.lifecycle = lifecycle;
        this.rail = rail;
        this.config = props.rail();
        this.clock = clock;
        this.meters = meters;
    }

    /** One pass. Returns the number of payouts that reached a final state. */
    public int runOnce() {
        List<Payment> claimed = tx.execute(s -> {
            var due = payments.lockDuePayouts(clock.instant(), config.batchSize());
            for (var p : due) {
                payments.recordRailAttempt(p.id(), p.railAttempts(), clock.instant().plus(config.lease()), null);
            }
            return due;
        });
        if (claimed == null) {
            return 0;
        }
        int finished = 0;
        for (var p : claimed) {
            if (process(p)) {
                finished++;
            }
        }
        return finished;
    }

    private boolean process(Payment p) {
        try {
            var result = rail.submit(new RailClient.Instruction(p.id().toString(), p.destinationRef(), p.amountMinor(), p.currency()));
            switch (result.outcome()) {
                case SETTLED -> {
                    lifecycle.railSettled(p.id(), result.railReference());
                    meters.counter("paynexa.payouts.rail", "outcome", "settled").increment();
                    return true;
                }
                case REJECTED -> {
                    lifecycle.railRejected(p.id(), "RAIL_REJECTED");
                    meters.counter("paynexa.payouts.rail", "outcome", "rejected").increment();
                    return true;
                }
                case PENDING -> {
                    reschedule(p, result.railReference());
                    meters.counter("paynexa.payouts.rail", "outcome", "pending").increment();
                    return false;
                }
            }
            return false;
        } catch (RailClient.RailUnavailableException e) {
            reschedule(p, null);
            meters.counter("paynexa.payouts.rail", "outcome", "unavailable").increment();
            log.warn("rail unavailable for payout {} (attempt {}): {}", p.id(), p.railAttempts() + 1, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // e.g. the database was unavailable while applying the outcome: leave SUBMITTED, lease will expire
            meters.counter("paynexa.payouts.rail", "outcome", "error").increment();
            log.error("could not apply rail outcome for payout {}", p.id(), e);
            return false;
        }
    }

    private void reschedule(Payment p, String railReference) {
        int attempts = p.railAttempts() + 1;
        var next = clock.instant().plus(backoff(attempts));
        tx.executeWithoutResult(s -> payments.recordRailAttempt(p.id(), attempts, next, railReference));
        if (attempts >= config.maxAttempts()) {
            meters.counter("paynexa.payouts.attempts_exhausted").increment();
        }
    }

    Duration backoff(int attempts) {
        long millis = config.baseBackoff().toMillis() * (1L << Math.min(attempts - 1, 20));
        return Duration.ofMillis(Math.min(millis, config.maxBackoff().toMillis()));
    }
}
