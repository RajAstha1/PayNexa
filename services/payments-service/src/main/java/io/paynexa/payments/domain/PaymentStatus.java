package io.paynexa.payments.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The payment state machine. This table is the single definition of legal transitions; the repository applies
 * them with compare-and-set so concurrent actors cannot both win.
 */
public enum PaymentStatus {
    PENDING_REVIEW,
    COMPLETED,
    SUBMITTED,
    SETTLED,
    FAILED,
    REJECTED,
    REVERSED;

    public Set<PaymentStatus> allowedNext(PaymentKind kind) {
        return switch (this) {
            case PENDING_REVIEW -> kind == PaymentKind.PAYOUT
                    ? EnumSet.of(SUBMITTED, REJECTED, FAILED)
                    : EnumSet.of(COMPLETED, REJECTED, FAILED);
            case COMPLETED -> kind == PaymentKind.TRANSFER ? EnumSet.of(REVERSED) : EnumSet.noneOf(PaymentStatus.class);
            case SUBMITTED -> kind == PaymentKind.PAYOUT ? EnumSet.of(SETTLED, REVERSED) : EnumSet.noneOf(PaymentStatus.class);
            case SETTLED, FAILED, REJECTED, REVERSED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canTransitionTo(PaymentKind kind, PaymentStatus next) {
        return allowedNext(kind).contains(next);
    }

    public boolean isTerminal(PaymentKind kind) {
        return allowedNext(kind).isEmpty();
    }

    /** Statuses in which funds have left the payer (used by risk limits). */
    public boolean movedMoney() {
        return this == COMPLETED || this == SUBMITTED || this == SETTLED;
    }
}
