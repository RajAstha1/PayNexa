package io.paynexa.payments.service;

import io.paynexa.payments.config.PaymentsProperties;
import io.paynexa.payments.domain.Account;
import io.paynexa.payments.domain.AccountType;
import io.paynexa.payments.domain.DomainException;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.domain.Payment;
import io.paynexa.payments.domain.PaymentKind;
import io.paynexa.payments.domain.PaymentStatus;
import io.paynexa.payments.persistence.AccountRepository;
import io.paynexa.payments.persistence.HistoryRepository;
import io.paynexa.payments.persistence.IdempotencyRepository;
import io.paynexa.payments.persistence.LedgerRepository;
import io.paynexa.payments.persistence.LedgerRepository.EntryKind;
import io.paynexa.payments.persistence.LedgerRepository.Leg;
import io.paynexa.payments.persistence.PaymentRepository;
import io.paynexa.payments.persistence.RiskRepository;
import io.paynexa.payments.risk.RiskDecision;
import io.paynexa.payments.risk.RiskEngine;
import io.paynexa.payments.service.PaymentRecorder.NewPayment;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Creates payments. Each public method is exactly one database transaction and makes no remote calls.
 *
 * <p>Outcome classes:
 * <ul>
 *   <li><b>Request errors</b> (bad input, unknown payee, not your account, currency mismatch): thrown as
 *       {@link DomainException}; the transaction rolls back completely, including the idempotency claim.</li>
 *   <li><b>Business outcomes</b> (insufficient funds, frozen account, risk block/review): committed as a payment in a
 *       terminal or review state, bound to the idempotency key, so a retry returns the same answer.</li>
 * </ul>
 */
@Service
public class PaymentService {

    public record Result(Payment payment, boolean replayed) {}

    public record TransferCommand(UUID payerAccountId, UUID payeeAccountId, Money amount, String reference) {}

    public record PayoutCommand(UUID payerAccountId, String destination, Money amount, String reference) {}

    private static final int MAX_TX_ATTEMPTS = 3;

    private final TransactionTemplate tx;
    private final AccountRepository accounts;
    private final PaymentRepository payments;
    private final LedgerRepository ledger;
    private final IdempotencyRepository idempotency;
    private final RiskEngine risk;
    private final RiskRepository riskRepo;
    private final PaymentRecorder recorder;
    private final HistoryRepository audit;
    private final PaymentsProperties props;
    private final Clock clock;
    private final PaymentMetrics metrics;

    public PaymentService(TransactionTemplate tx, AccountRepository accounts, PaymentRepository payments,
                          LedgerRepository ledger, IdempotencyRepository idempotency, RiskEngine risk,
                          RiskRepository riskRepo, PaymentRecorder recorder, HistoryRepository audit,
                          PaymentsProperties props, Clock clock, PaymentMetrics metrics) {
        this.tx = tx;
        this.accounts = accounts;
        this.payments = payments;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.risk = risk;
        this.riskRepo = riskRepo;
        this.recorder = recorder;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------------------------------------ transfer

    public Result transfer(Actor actor, String idempotencyKey, TransferCommand cmd) {
        requireKey(idempotencyKey);
        var fingerprint = fingerprint("TRANSFER", cmd.payerAccountId(), cmd.payeeAccountId(), cmd.amount(), cmd.reference());
        return withRetry(() -> tx.execute(s -> {
            var paymentId = UUID.randomUUID();
            var replay = claimOrReplay(actor, idempotencyKey, fingerprint, paymentId);
            if (replay != null) {
                return replay;
            }
            var locked = accounts.lockInOrder(List.of(cmd.payerAccountId(), cmd.payeeAccountId()));
            var payer = ownedCustomerAccount(actor, locked.get(cmd.payerAccountId()));
            var payee = locked.get(cmd.payeeAccountId());
            if (payee == null || payee.type() != AccountType.CUSTOMER) {
                throw DomainException.notFound("PAYEE_NOT_FOUND", "Payee account not found");
            }
            if (payer.id().equals(payee.id())) {
                throw new DomainException("SAME_ACCOUNT", "Payer and payee must be different accounts");
            }
            requireCurrency(payer, payee, cmd.amount());

            var np = new NewPayment(PaymentKind.TRANSFER, actor.userId(), payer.id(), payee.id(), null,
                    cmd.amount().minor(), cmd.amount().currency(), cmd.reference());
            if (!payer.isActive()) {
                return business(paymentId, np, PaymentStatus.FAILED, "ACCOUNT_FROZEN", actor, null);
            }
            if (!payee.isActive()) {
                return business(paymentId, np, PaymentStatus.FAILED, "PAYEE_UNAVAILABLE", actor, null);
            }
            var outcome = assess(np, cmd.amount());
            switch (outcome.decision()) {
                case BLOCK -> {
                    return business(paymentId, np, PaymentStatus.REJECTED, "RISK_BLOCKED", actor, outcome);
                }
                case REVIEW -> {
                    return business(paymentId, np, PaymentStatus.PENDING_REVIEW, null, actor, outcome);
                }
                default -> { /* ALLOW falls through to funds check */ }
            }
            if (payer.balanceMinor() < cmd.amount().minor()) {
                return business(paymentId, np, PaymentStatus.FAILED, "INSUFFICIENT_FUNDS", actor, outcome);
            }
            var payment = recorder.create(paymentId, np, PaymentStatus.COMPLETED, null, actor.name());
            ledger.post(paymentId, EntryKind.TRANSFER, null, List.of(
                    Leg.debit(payer.id(), np.amountMinor(), np.currency()),
                    Leg.credit(payee.id(), np.amountMinor(), np.currency())));
            riskRepo.record(paymentId, outcome);
            metrics.created(PaymentKind.TRANSFER, payment.status());
            return new Result(payment, false);
        }));
    }

    // ------------------------------------------------------------------------------------------------- payout

    public Result payout(Actor actor, String idempotencyKey, PayoutCommand cmd) {
        requireKey(idempotencyKey);
        var destination = cmd.destination() == null ? "" : cmd.destination().trim();
        if (destination.isEmpty() || destination.length() > 140 || !destination.matches("[A-Za-z0-9 ._:/@+-]+")) {
            throw new DomainException("INVALID_DESTINATION", "Destination is missing or contains unsupported characters");
        }
        var fingerprint = fingerprint("PAYOUT", cmd.payerAccountId(), destination, cmd.amount(), cmd.reference());
        return withRetry(() -> tx.execute(s -> {
            var paymentId = UUID.randomUUID();
            var replay = claimOrReplay(actor, idempotencyKey, fingerprint, paymentId);
            if (replay != null) {
                return replay;
            }
            var locked = accounts.lockInOrder(List.of(cmd.payerAccountId()));
            var payer = ownedCustomerAccount(actor, locked.get(cmd.payerAccountId()));
            if (!payer.currency().equals(cmd.amount().currency())) {
                throw new DomainException("CURRENCY_MISMATCH", "Payment currency must match the account currency");
            }
            var np = new NewPayment(PaymentKind.PAYOUT, actor.userId(), payer.id(), null, destination,
                    cmd.amount().minor(), cmd.amount().currency(), cmd.reference());
            if (!payer.isActive()) {
                return business(paymentId, np, PaymentStatus.FAILED, "ACCOUNT_FROZEN", actor, null);
            }
            var outcome = assess(np, cmd.amount());
            switch (outcome.decision()) {
                case BLOCK -> {
                    return business(paymentId, np, PaymentStatus.REJECTED, "RISK_BLOCKED", actor, outcome);
                }
                case REVIEW -> {
                    return business(paymentId, np, PaymentStatus.PENDING_REVIEW, null, actor, outcome);
                }
                default -> { /* ALLOW */ }
            }
            if (payer.balanceMinor() < cmd.amount().minor()) {
                return business(paymentId, np, PaymentStatus.FAILED, "INSUFFICIENT_FUNDS", actor, outcome);
            }
            // Money leaves the customer but is NOT settled: it sits in payout clearing until the rail confirms.
            var clearing = accounts.systemAccount(AccountType.PAYOUT_CLEARING, np.currency());
            var payment = recorder.create(paymentId, np, PaymentStatus.SUBMITTED, null, actor.name());
            ledger.post(paymentId, EntryKind.PAYOUT_SUBMIT, null, List.of(
                    Leg.debit(payer.id(), np.amountMinor(), np.currency()),
                    Leg.credit(clearing.id(), np.amountMinor(), np.currency())));
            riskRepo.record(paymentId, outcome);
            metrics.created(PaymentKind.PAYOUT, payment.status());
            return new Result(payment, false);
        }));
    }

    // ------------------------------------------------------------------------------------- sandbox funding

    /** Synthetic top-up for development and tests. Disabled unless explicitly enabled by configuration. */
    public Result fund(Actor admin, String idempotencyKey, UUID accountId, Money amount) {
        if (!props.sandbox().fundingEnabled()) {
            throw DomainException.notFound("NOT_FOUND", "Not found");
        }
        if (!admin.admin()) {
            throw new DomainException("FORBIDDEN", "Administrator role required", DomainException.Category.FORBIDDEN);
        }
        requireKey(idempotencyKey);
        var fingerprint = fingerprint("FUNDING", accountId, null, amount, null);
        return withRetry(() -> tx.execute(s -> {
            var paymentId = UUID.randomUUID();
            var replay = claimOrReplay(admin, idempotencyKey, fingerprint, paymentId);
            if (replay != null) {
                return replay;
            }
            var target = accounts.lockInOrder(List.of(accountId)).get(accountId);
            if (target == null || target.type() != AccountType.CUSTOMER) {
                throw DomainException.notFound("ACCOUNT_NOT_FOUND", "Account not found");
            }
            if (!target.currency().equals(amount.currency())) {
                throw new DomainException("CURRENCY_MISMATCH", "Payment currency must match the account currency");
            }
            var source = accounts.systemAccount(AccountType.FUNDING_SOURCE, amount.currency());
            var np = new NewPayment(PaymentKind.FUNDING, admin.userId(), source.id(), target.id(), null,
                    amount.minor(), amount.currency(), "sandbox funding");
            var payment = recorder.create(paymentId, np, PaymentStatus.COMPLETED, null, admin.name());
            ledger.post(paymentId, EntryKind.FUNDING, null, List.of(
                    Leg.debit(source.id(), amount.minor(), amount.currency()),
                    Leg.credit(target.id(), amount.minor(), amount.currency())));
            audit.audit(admin.name(), "SANDBOX_FUNDING", "account", accountId.toString(),
                    "{\"paymentId\":\"%s\",\"amountMinor\":%d,\"currency\":\"%s\"}".formatted(paymentId, amount.minor(), amount.currency()));
            metrics.created(PaymentKind.FUNDING, payment.status());
            return new Result(payment, false);
        }));
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private Result claimOrReplay(Actor actor, String key, String fingerprint, UUID newPaymentId) {
        if (idempotency.claim(actor.userId(), key, fingerprint, newPaymentId)) {
            return null;
        }
        var stored = idempotency.find(actor.userId(), key)
                .orElseThrow(() -> DomainException.conflict("IDEMPOTENCY_CONFLICT", "Please retry the request"));
        if (!stored.fingerprint().equals(fingerprint)) {
            throw DomainException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request");
        }
        var existing = payments.find(stored.paymentId())
                .orElseThrow(() -> DomainException.conflict("IDEMPOTENCY_CONFLICT", "Please retry the request"));
        metrics.replayed();
        return new Result(existing, true);
    }

    private Result business(UUID paymentId, NewPayment np, PaymentStatus status, String reason, Actor actor,
                            RiskDecision.Outcome outcome) {
        var payment = recorder.create(paymentId, np, status, reason, actor.name());
        if (outcome != null) {
            riskRepo.record(paymentId, outcome);
        }
        metrics.created(np.kind(), status);
        return new Result(payment, false);
    }

    private RiskDecision.Outcome assess(NewPayment np, Money amount) {
        return risk.evaluate(new RiskEngine.RiskContextInput(np.kind(), np.payerAccountId(), np.payeeAccountId(),
                amount, clock.instant()), riskRepo);
    }

    private static Account ownedCustomerAccount(Actor actor, Account account) {
        // Not-yours and does-not-exist are indistinguishable on purpose.
        if (account == null || account.type() != AccountType.CUSTOMER || !actor.userId().equals(account.ownerUserId())) {
            throw DomainException.notFound("ACCOUNT_NOT_FOUND", "Account not found");
        }
        return account;
    }

    private static void requireCurrency(Account payer, Account payee, Money amount) {
        if (!payer.currency().equals(amount.currency()) || !payee.currency().equals(amount.currency())) {
            throw new DomainException("CURRENCY_MISMATCH", "Payment currency must match both account currencies");
        }
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,64}")) {
            throw new DomainException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required: 8-64 characters from A-Z a-z 0-9 . _ : -");
        }
    }

    static String fingerprint(Object... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var sb = new StringBuilder();
            for (var part : parts) {
                sb.append(part == null ? "" : part).append('\u001f');
            }
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Retries the whole (idempotent) operation on transient database failures such as a deadlock victim or lost
     * connection. Safe because each attempt is a complete transaction guarded by the idempotency key.
     */
    private <T> T withRetry(Supplier<T> op) {
        TransientDataAccessException last = null;
        for (int attempt = 1; attempt <= MAX_TX_ATTEMPTS; attempt++) {
            try {
                return op.get();
            } catch (TransientDataAccessException e) {
                last = e;
                metrics.transientRetry();
                try {
                    Thread.sleep(20L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }
}
