package io.paynexa.payments.service;

import io.paynexa.payments.domain.AccountType;
import io.paynexa.payments.domain.DomainException;
import io.paynexa.payments.domain.InvalidTransitionException;
import io.paynexa.payments.domain.Payment;
import io.paynexa.payments.domain.PaymentKind;
import io.paynexa.payments.domain.PaymentStatus;
import io.paynexa.payments.persistence.AccountRepository;
import io.paynexa.payments.persistence.HistoryRepository;
import io.paynexa.payments.persistence.LedgerRepository;
import io.paynexa.payments.persistence.LedgerRepository.EntryKind;
import io.paynexa.payments.persistence.LedgerRepository.Leg;
import io.paynexa.payments.persistence.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * State changes after creation: admin review, reversal, and rail outcomes. One transaction each, no remote calls.
 * Rail outcome methods are idempotent so a redelivered or retried rail callback has no extra financial effect.
 */
@Service
public class LifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);

    private final TransactionTemplate tx;
    private final PaymentRepository payments;
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final PaymentRecorder recorder;
    private final HistoryRepository audit;
    private final PaymentMetrics metrics;

    public LifecycleService(TransactionTemplate tx, PaymentRepository payments, AccountRepository accounts,
                            LedgerRepository ledger, PaymentRecorder recorder, HistoryRepository audit,
                            PaymentMetrics metrics) {
        this.tx = tx;
        this.payments = payments;
        this.accounts = accounts;
        this.ledger = ledger;
        this.recorder = recorder;
        this.audit = audit;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------------------------------ admin review

    public Payment approve(Actor admin, UUID paymentId) {
        requireAdmin(admin);
        return tx.execute(s -> {
            var p = lockPayment(paymentId);
            requireStatus(p, PaymentStatus.PENDING_REVIEW, "approve");
            // Four-eyes control: the person who created a payment cannot release it from review.
            if (admin.userId().equals(p.initiatorUserId())) {
                throw new DomainException("SELF_APPROVAL_NOT_ALLOWED",
                        "A payment cannot be approved by the user who created it", DomainException.Category.FORBIDDEN);
            }
            var ids = new ArrayList<UUID>();
            ids.add(p.payerAccountId());
            if (p.payeeAccountId() != null) {
                ids.add(p.payeeAccountId());
            }
            var locked = accounts.lockInOrder(ids);
            var payer = locked.get(p.payerAccountId());
            var payee = p.payeeAccountId() == null ? null : locked.get(p.payeeAccountId());

            // Conditions may have changed while the payment waited; re-check everything that matters.
            if (!payer.isActive()) {
                return fail(p, "ACCOUNT_FROZEN", admin);
            }
            if (payee != null && !payee.isActive()) {
                return fail(p, "PAYEE_UNAVAILABLE", admin);
            }
            if (payer.balanceMinor() < p.amountMinor()) {
                return fail(p, "INSUFFICIENT_FUNDS", admin);
            }
            Payment done;
            if (p.kind() == PaymentKind.PAYOUT) {
                var clearing = accounts.systemAccount(AccountType.PAYOUT_CLEARING, p.currency());
                done = recorder.transition(p, PaymentStatus.SUBMITTED, null, admin.name());
                ledger.post(p.id(), EntryKind.PAYOUT_SUBMIT, null, List.of(
                        Leg.debit(payer.id(), p.amountMinor(), p.currency()),
                        Leg.credit(clearing.id(), p.amountMinor(), p.currency())));
            } else {
                done = recorder.transition(p, PaymentStatus.COMPLETED, null, admin.name());
                ledger.post(p.id(), EntryKind.TRANSFER, null, List.of(
                        Leg.debit(payer.id(), p.amountMinor(), p.currency()),
                        Leg.credit(payee.id(), p.amountMinor(), p.currency())));
            }
            audit.audit(admin.name(), "PAYMENT_APPROVED", "payment", p.id().toString(), "{}");
            metrics.transitioned(p.kind(), done.status());
            return done;
        });
    }

    public Payment reject(Actor admin, UUID paymentId, String reason) {
        requireAdmin(admin);
        return tx.execute(s -> {
            var p = lockPayment(paymentId);
            requireStatus(p, PaymentStatus.PENDING_REVIEW, "reject");
            var done = recorder.transition(p, PaymentStatus.REJECTED, "REVIEW_REJECTED", admin.name());
            audit.audit(admin.name(), "PAYMENT_REJECTED", "payment", p.id().toString(),
                    "{\"reason\":\"%s\"}".formatted(AccountService.safe(reason)));
            metrics.transitioned(p.kind(), done.status());
            return done;
        });
    }

    /** Compensating entry for a completed transfer. The original entry is never touched. */
    public Payment reverse(Actor admin, UUID paymentId, String reason) {
        requireAdmin(admin);
        return tx.execute(s -> {
            var p = lockPayment(paymentId);
            if (p.kind() != PaymentKind.TRANSFER) {
                throw new DomainException("NOT_REVERSIBLE", "Only transfers can be reversed here", DomainException.Category.CONFLICT);
            }
            requireStatus(p, PaymentStatus.COMPLETED, "reverse");
            var locked = accounts.lockInOrder(List.of(p.payerAccountId(), p.payeeAccountId()));
            var payee = locked.get(p.payeeAccountId());
            if (payee.balanceMinor() < p.amountMinor()) {
                // Balances never go negative. Recovering funds the payee already spent is out of scope.
                throw DomainException.conflict("INSUFFICIENT_FUNDS_FOR_REVERSAL",
                        "The payee no longer holds enough funds to reverse this payment");
            }
            var original = ledger.findEntry(p.id(), EntryKind.TRANSFER)
                    .orElseThrow(() -> new IllegalStateException("Completed transfer %s has no ledger entry".formatted(p.id())));
            var done = recorder.transition(p, PaymentStatus.REVERSED, "REVERSED_BY_ADMIN", admin.name());
            ledger.post(p.id(), EntryKind.REVERSAL, original.id(),
                    original.legs().stream().map(Leg::opposite).toList());
            audit.audit(admin.name(), "PAYMENT_REVERSED", "payment", p.id().toString(),
                    "{\"reason\":\"%s\"}".formatted(AccountService.safe(reason)));
            metrics.transitioned(p.kind(), done.status());
            return done;
        });
    }

    // ---------------------------------------------------------------------------------------- rail outcomes

    /** Rail confirmed delivery. Only now does the payout count as settled. Idempotent. */
    public Payment railSettled(UUID paymentId, String railReference) {
        return tx.execute(s -> {
            var p = lockPayment(paymentId);
            if (p.status() == PaymentStatus.SETTLED) {
                return p;
            }
            if (p.status() != PaymentStatus.SUBMITTED) {
                log.error("rail reported settlement for payment {} which is {}; manual investigation required", p.id(), p.status());
                metrics.transitioned(p.kind(), p.status());
                throw new InvalidTransitionException(p.status(), PaymentStatus.SETTLED);
            }
            var clearing = accounts.systemAccount(AccountType.PAYOUT_CLEARING, p.currency());
            var external = accounts.systemAccount(AccountType.EXTERNAL_SETTLED, p.currency());
            var done = recorder.transition(p, PaymentStatus.SETTLED, null, "system:rail");
            payments.setRailReference(p.id(), railReference);
            ledger.post(p.id(), EntryKind.PAYOUT_SETTLE, null, List.of(
                    Leg.debit(clearing.id(), p.amountMinor(), p.currency()),
                    Leg.credit(external.id(), p.amountMinor(), p.currency())));
            metrics.transitioned(p.kind(), done.status());
            return done;
        });
    }

    /** Rail rejected (or definitively failed) the payout: return the money with a compensating entry. Idempotent. */
    public Payment railRejected(UUID paymentId, String reason) {
        return tx.execute(s -> {
            var p = lockPayment(paymentId);
            if (p.status() == PaymentStatus.REVERSED) {
                return p;
            }
            if (p.status() != PaymentStatus.SUBMITTED) {
                log.error("rail reported rejection for payment {} which is {}; manual investigation required", p.id(), p.status());
                throw new InvalidTransitionException(p.status(), PaymentStatus.REVERSED);
            }
            accounts.lockInOrder(List.of(p.payerAccountId()));
            var clearing = accounts.systemAccount(AccountType.PAYOUT_CLEARING, p.currency());
            var done = recorder.transition(p, PaymentStatus.REVERSED, reason == null ? "RAIL_REJECTED" : reason, "system:rail");
            ledger.post(p.id(), EntryKind.PAYOUT_RETURN, null, List.of(
                    Leg.debit(clearing.id(), p.amountMinor(), p.currency()),
                    Leg.credit(p.payerAccountId(), p.amountMinor(), p.currency())));
            metrics.transitioned(p.kind(), done.status());
            return done;
        });
    }

    // -------------------------------------------------------------------------------------------- internals

    private Payment fail(Payment p, String reason, Actor actor) {
        var done = recorder.transition(p, PaymentStatus.FAILED, reason, actor.name());
        audit.audit(actor.name(), "PAYMENT_APPROVAL_FAILED", "payment", p.id().toString(),
                "{\"reason\":\"%s\"}".formatted(reason));
        metrics.transitioned(p.kind(), done.status());
        return done;
    }

    private Payment lockPayment(UUID id) {
        return payments.findForUpdate(id).orElseThrow(() -> DomainException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
    }

    private static void requireStatus(Payment p, PaymentStatus expected, String action) {
        if (p.status() != expected) {
            throw new InvalidTransitionException(p.status(), action);
        }
    }

    private static void requireAdmin(Actor actor) {
        if (!actor.admin()) {
            throw new DomainException("FORBIDDEN", "Administrator role required", DomainException.Category.FORBIDDEN);
        }
    }
}
