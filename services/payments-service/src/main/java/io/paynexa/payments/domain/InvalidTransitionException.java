package io.paynexa.payments.domain;

public class InvalidTransitionException extends DomainException {
    public InvalidTransitionException(PaymentStatus from, PaymentStatus to) {
        super("INVALID_STATE_TRANSITION", "Payment cannot move from %s to %s".formatted(from, to), Category.CONFLICT);
    }

    public InvalidTransitionException(PaymentStatus current, String action) {
        super("INVALID_STATE_TRANSITION", "Cannot %s a payment that is %s".formatted(action, current), Category.CONFLICT);
    }
}
