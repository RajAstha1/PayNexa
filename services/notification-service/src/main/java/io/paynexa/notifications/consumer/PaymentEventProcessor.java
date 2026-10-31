package io.paynexa.notifications.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import io.paynexa.contracts.PaymentEvent;
import io.paynexa.notifications.persistence.NotificationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Turns payment events into notifications with these guarantees, all decided inside one database transaction:
 * <ul>
 *   <li><b>Duplicate delivery</b>: the event id is claimed in the inbox; a second delivery changes nothing.</li>
 *   <li><b>Out-of-order delivery</b>: an event whose sequence is not newer than what was already applied for the same
 *       payment is recorded as STALE and produces no notification.</li>
 *   <li><b>Atomicity</b>: inbox row, progress and notifications commit together or not at all, so a failure after
 *       partial work can be retried without duplicates or gaps.</li>
 * </ul>
 * Delivery to a real channel (email/SMS/push) is out of scope: a notification here is a stored record.
 */
@Component
public class PaymentEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventProcessor.class);

    /** Thrown for payloads that can never succeed; the error handler sends these straight to the dead-letter topic. */
    public static final class UnprocessableEventException extends RuntimeException {
        public UnprocessableEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public enum Outcome { APPLIED, DUPLICATE, STALE }

    private final NotificationStore store;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public PaymentEventProcessor(NotificationStore store, TransactionTemplate tx, MeterRegistry meters) {
        this.store = store;
        this.tx = tx;
        this.meters = meters;
    }

    public Outcome process(String payload) {
        PaymentEvent event;
        try {
            event = json.readValue(payload, PaymentEvent.class);
        } catch (RuntimeException e) {
            throw new UnprocessableEventException("event payload could not be parsed", e);
        }
        if (event.eventId() == null || event.paymentId() == null || event.type() == null || event.sequence() < 1) {
            throw new UnprocessableEventException("event is missing required fields", null);
        }
        var outcome = tx.execute(status -> apply(event));
        meters.counter("paynexa.notifications.events", "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)).increment();
        return outcome;
    }

    private Outcome apply(PaymentEvent e) {
        if (!store.claimEvent(e.eventId(), e.type(), e.paymentId(), e.sequence())) {
            log.debug("duplicate event {} ignored", e.eventId());
            return Outcome.DUPLICATE;
        }
        if (!store.advanceProgress(e.paymentId(), e.sequence())) {
            store.markStale(e.eventId());
            log.info("stale event {} for payment {} (sequence {}) ignored", e.eventId(), e.paymentId(), e.sequence());
            return Outcome.STALE;
        }
        var amount = format(e.amountMinor(), e.currency());
        switch (e.type()) {
            case PaymentEvent.COMPLETED -> {
                notifyUser(e, e.payerUserId(), "PAYMENT_SENT", "Your payment of " + amount + " was completed.");
                if (e.payeeUserId() != null && !e.payeeUserId().equals(e.payerUserId()) && "TRANSFER".equals(e.kind())) {
                    notifyUser(e, e.payeeUserId(), "PAYMENT_RECEIVED", "You received " + amount + ".");
                }
            }
            case PaymentEvent.PENDING_REVIEW -> notifyUser(e, e.payerUserId(), "PAYMENT_UNDER_REVIEW", "Your payment of " + amount + " is under review.");
            case PaymentEvent.REJECTED -> notifyUser(e, e.payerUserId(), "PAYMENT_REJECTED", "Your payment of " + amount + " was not approved.");
            case PaymentEvent.FAILED -> notifyUser(e, e.payerUserId(), "PAYMENT_FAILED", "Your payment of " + amount + " failed" + reason(e) + ".");
            case PaymentEvent.SUBMITTED -> notifyUser(e, e.payerUserId(), "PAYOUT_SUBMITTED", "Your payout of " + amount + " was submitted and is awaiting confirmation.");
            case PaymentEvent.SETTLED -> notifyUser(e, e.payerUserId(), "PAYOUT_SETTLED", "Your payout of " + amount + " was confirmed as delivered.");
            case PaymentEvent.REVERSED -> {
                notifyUser(e, e.payerUserId(), "PAYMENT_REVERSED", "A payment of " + amount + " was reversed and the funds were returned.");
                if (e.payeeUserId() != null && "TRANSFER".equals(e.kind())) {
                    notifyUser(e, e.payeeUserId(), "PAYMENT_REVERSED", "A payment of " + amount + " you received was reversed.");
                }
            }
            default -> log.info("event type {} has no notification", e.type());   // forward compatible: unknown types are ignored
        }
        return Outcome.APPLIED;
    }

    private void notifyUser(PaymentEvent e, java.util.UUID user, String kind, String message) {
        if (user != null) {
            store.addNotification(user, e.paymentId(), e.eventId(), kind, message);
        }
    }

    /** Only a fixed, code-like reason is ever included; free text from upstream never reaches a user message. */
    private static String reason(PaymentEvent e) {
        return e.failureReason() != null && e.failureReason().matches("[A-Z_]{3,40}")
                ? " (" + e.failureReason().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")" : "";
    }

    static String format(long minor, String currency) {
        try {
            int digits = Currency.getInstance(currency).getDefaultFractionDigits();
            return BigDecimal.valueOf(minor, digits).toPlainString() + " " + currency;
        } catch (RuntimeException ex) {
            return minor + " (minor units)";
        }
    }
}
