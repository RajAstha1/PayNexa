package io.paynexa.payments.service;

import io.paynexa.contracts.PaymentEvent;
import io.paynexa.payments.domain.InvalidTransitionException;
import io.paynexa.payments.domain.Payment;
import io.paynexa.payments.domain.PaymentKind;
import io.paynexa.payments.domain.PaymentStatus;
import io.paynexa.payments.persistence.AccountRepository;
import io.paynexa.payments.persistence.HistoryRepository;
import io.paynexa.payments.persistence.OutboxRepository;
import io.paynexa.payments.persistence.PaymentRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The only place that creates payments or changes their status. Every change records history and writes the
 * outbox event in the caller's transaction, so state, history and event can never disagree.
 */
@Component
public class PaymentRecorder {

    public record NewPayment(PaymentKind kind, UUID initiatorUserId, UUID payerAccountId, UUID payeeAccountId,
                             String destinationRef, long amountMinor, String currency, String reference) {}

    private final PaymentRepository payments;
    private final AccountRepository accounts;
    private final HistoryRepository history;
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final Clock clock;

    public PaymentRecorder(PaymentRepository payments, AccountRepository accounts, HistoryRepository history,
                           OutboxRepository outbox, ObjectMapper json, Clock clock) {
        this.payments = payments;
        this.accounts = accounts;
        this.history = history;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    public Payment create(UUID id, NewPayment np, PaymentStatus status, String failureReason, String actor) {
        var now = clock.instant();
        var payment = new Payment(id, np.kind(), status, np.initiatorUserId(), np.payerAccountId(), np.payeeAccountId(),
                np.destinationRef(), np.amountMinor(), np.currency(), np.reference(), failureReason, 1, null, 0,
                status == PaymentStatus.SUBMITTED ? now : null, now, now);
        payments.insert(payment);
        history.recordTransition(id, null, status, actor, failureReason);
        emit(payment);
        return payment;
    }

    public Payment transition(Payment current, PaymentStatus next, String reason, String actor) {
        if (!current.status().canTransitionTo(current.kind(), next)) {
            throw new InvalidTransitionException(current.status(), next);
        }
        var updated = payments.transition(current, next, reason);
        history.recordTransition(current.id(), current.status(), next, actor, reason);
        emit(updated);
        return updated;
    }

    private void emit(Payment p) {
        var payerOwner = accounts.find(p.payerAccountId()).map(a -> a.ownerUserId()).orElse(null);
        var payeeOwner = p.payeeAccountId() == null ? null : accounts.find(p.payeeAccountId()).map(a -> a.ownerUserId()).orElse(null);
        var event = new PaymentEvent(UUID.randomUUID(), typeFor(p.status()), Instant.now(clock), p.id(), p.sequence(),
                p.kind().name(), p.status().name(), payerOwner, payeeOwner, p.amountMinor(), p.currency(),
                p.status() == PaymentStatus.FAILED || p.status() == PaymentStatus.REJECTED || p.status() == PaymentStatus.REVERSED
                        ? p.failureReason() : null);
        outbox.insert(event.eventId(), p.id(), event.type(), p.sequence(), json.writeValueAsString(event));
    }

    static String typeFor(PaymentStatus s) {
        return switch (s) {
            case COMPLETED -> PaymentEvent.COMPLETED;
            case PENDING_REVIEW -> PaymentEvent.PENDING_REVIEW;
            case REJECTED -> PaymentEvent.REJECTED;
            case FAILED -> PaymentEvent.FAILED;
            case SUBMITTED -> PaymentEvent.SUBMITTED;
            case SETTLED -> PaymentEvent.SETTLED;
            case REVERSED -> PaymentEvent.REVERSED;
        };
    }
}
