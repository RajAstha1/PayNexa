package io.paynexa.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published by payments-service on topic {@link #TOPIC}, keyed by {@code paymentId}.
 *
 * <p>Contract rules: additive changes only; consumers must ignore unknown fields. {@code sequence} increases by one
 * with every state change of a payment, so consumers can discard stale events that arrive out of order.
 * {@code eventId} is unique per event and is the deduplication key. Carries no credentials, destination details or
 * free-text references.
 */
public record PaymentEvent(
        UUID eventId,
        String type,
        Instant occurredAt,
        UUID paymentId,
        int sequence,
        String kind,
        String status,
        UUID payerUserId,
        UUID payeeUserId,
        long amountMinor,
        String currency,
        String failureReason) {

    public static final String TOPIC = "paynexa.payments.v1";
    public static final String DEAD_LETTER_SUFFIX = ".DLT";

    // Event types
    public static final String COMPLETED = "payment.completed";
    public static final String PENDING_REVIEW = "payment.pending_review";
    public static final String REJECTED = "payment.rejected";
    public static final String FAILED = "payment.failed";
    public static final String SUBMITTED = "payment.submitted";
    public static final String SETTLED = "payment.settled";
    public static final String REVERSED = "payment.reversed";
}
