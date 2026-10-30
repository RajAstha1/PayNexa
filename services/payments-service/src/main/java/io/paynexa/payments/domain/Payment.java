package io.paynexa.payments.domain;

import java.time.Instant;
import java.util.UUID;

public record Payment(
        UUID id,
        PaymentKind kind,
        PaymentStatus status,
        UUID initiatorUserId,
        UUID payerAccountId,
        UUID payeeAccountId,
        String destinationRef,
        long amountMinor,
        String currency,
        String reference,
        String failureReason,
        int sequence,
        String railReference,
        int railAttempts,
        Instant railNextAttemptAt,
        Instant createdAt,
        Instant updatedAt) {

    public Money amount() {
        return new Money(amountMinor, currency);
    }

    /** Never prints destination details or references. */
    @Override
    public String toString() {
        return "Payment[id=%s, kind=%s, status=%s, amountMinor=%d %s]".formatted(id, kind, status, amountMinor, currency);
    }
}
