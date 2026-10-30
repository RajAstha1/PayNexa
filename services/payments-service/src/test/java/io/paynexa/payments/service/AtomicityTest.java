package io.paynexa.payments.service;

import io.paynexa.payments.persistence.LedgerRepository;
import io.paynexa.payments.persistence.LedgerRepository.Leg;
import io.paynexa.payments.support.TestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A failure anywhere inside the money transaction must leave no payment, entry, history row, event or balance change. */
class AtomicityTest {

    static TestKit kit;

    @BeforeAll
    static void start() {
        kit = TestKit.create();
    }

    @AfterAll
    static void stop() {
        kit.close();
    }

    @Test
    void unbalancedLedgerWriteAtCommitRollsBackThePaymentHistoryAndOutboxToo() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        long outboxBefore = kit.count("SELECT count(*) FROM outbox_events");
        long paymentsBefore = kit.count("SELECT count(*) FROM payments");
        var paymentId = UUID.randomUUID();

        // Simulates a bug in application code: payment, history and event are written, but the ledger legs do not balance.
        assertThatThrownBy(() -> kit.tx.executeWithoutResult(s -> {
            kit.recorder.create(paymentId,
                    new PaymentRecorder.NewPayment(io.paynexa.payments.domain.PaymentKind.TRANSFER, alice.userId(), a.id(), b.id(), null, 1_000, "USD", null),
                    io.paynexa.payments.domain.PaymentStatus.COMPLETED, null, "test");
            kit.ledger.post(paymentId, LedgerRepository.EntryKind.TRANSFER, null, List.of(
                    Leg.debit(a.id(), 1_000, "USD"), Leg.credit(b.id(), 999, "USD")));
        })).isInstanceOf(org.springframework.transaction.TransactionSystemException.class)
                .hasStackTraceContaining("not balanced"); // deferred trigger fires at COMMIT

        assertThat(kit.count("SELECT count(*) FROM payments")).isEqualTo(paymentsBefore);
        assertThat(kit.count("SELECT count(*) FROM outbox_events")).isEqualTo(outboxBefore);
        assertThat(kit.count("SELECT count(*) FROM payment_status_history WHERE payment_id = ?", paymentId)).isZero();
        assertThat(kit.count("SELECT count(*) FROM journal_entries WHERE payment_id = ?", paymentId)).isZero();
        assertThat(kit.balance(a.id())).isEqualTo(5_000);
        assertThat(kit.balance(b.id())).isZero();
    }

    @Test
    void overdraftAttemptDeepInsideTheTransactionRollsEverythingBack() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "10.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var paymentId = UUID.randomUUID();
        long historyBefore = kit.count("SELECT count(*) FROM payment_status_history");

        assertThatThrownBy(() -> kit.tx.executeWithoutResult(s -> {
            kit.recorder.create(paymentId,
                    new PaymentRecorder.NewPayment(io.paynexa.payments.domain.PaymentKind.TRANSFER, alice.userId(), a.id(), b.id(), null, 1_001, "USD", null),
                    io.paynexa.payments.domain.PaymentStatus.COMPLETED, null, "test");
            // application forgot the funds check; the database CHECK is the last line of defence
            kit.ledger.post(paymentId, LedgerRepository.EntryKind.TRANSFER, null, List.of(
                    Leg.debit(a.id(), 1_001, "USD"), Leg.credit(b.id(), 1_001, "USD")));
        })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("customer_balance_non_negative");

        assertThat(kit.count("SELECT count(*) FROM payment_status_history")).isEqualTo(historyBefore);
        assertThat(kit.balance(a.id())).isEqualTo(1_000);
        assertThat(kit.balance(b.id())).isZero();
    }

    @Test
    void failureAfterTheIdempotencyClaimLeavesTheKeyReusable() {
        var alice = kit.newUser();
        var k = "claimed-" + UUID.randomUUID();
        assertThatThrownBy(() -> kit.tx.executeWithoutResult(s -> {
            // claim, then fail before the payment row exists: the deferred FK makes the commit fail
            kit.idempotency.claim(alice.userId(), k, "fp", UUID.randomUUID());
        })).isInstanceOf(org.springframework.transaction.TransactionSystemException.class); // deferred FK fails at COMMIT
        assertThat(kit.idempotency.find(alice.userId(), k)).isEmpty();
    }
}
