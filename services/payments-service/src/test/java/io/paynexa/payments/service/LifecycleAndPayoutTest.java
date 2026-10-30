package io.paynexa.payments.service;

import io.paynexa.payments.config.PaymentsProperties;
import io.paynexa.payments.domain.AccountType;
import io.paynexa.payments.domain.DomainException;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.domain.PaymentStatus;
import io.paynexa.payments.persistence.LedgerRepository;
import io.paynexa.payments.service.PaymentService.PayoutCommand;
import io.paynexa.payments.service.PaymentService.TransferCommand;
import io.paynexa.payments.support.TestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleAndPayoutTest {

    static TestKit kit;

    @BeforeAll
    static void start() {
        // review threshold 100.00 so a larger transfer is held for an admin; block above 5000.00
        kit = TestKit.create(new PaymentsProperties.Limits(new BigDecimal("5000"), new BigDecimal("100"),
                new BigDecimal("100000"), new BigDecimal("1000000"), 1000, Duration.ofMinutes(10)));
    }

    @AfterAll
    static void stop() {
        kit.close();
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    // ------------------------------------------------------------------------------------------ review

    @Test
    void heldTransferMovesNoMoneyUntilAnotherAdminApproves() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "1000.00");
        var b = kit.openAccount(kit.newUser(), "USD");

        var held = kit.paymentService.transfer(alice, key(), new TransferCommand(a.id(), b.id(), Money.parse("250.00", "USD"), null)).payment();
        assertThat(held.status()).isEqualTo(PaymentStatus.PENDING_REVIEW);
        assertThat(kit.balance(a.id())).isEqualTo(100_000);
        assertThat(kit.balance(b.id())).isZero();
        assertThat(kit.count("SELECT count(*) FROM journal_entries WHERE payment_id = ?", held.id())).isZero();
        assertThat(kit.count("SELECT count(*) FROM risk_decisions WHERE payment_id = ? AND decision = 'REVIEW'", held.id())).isEqualTo(1);

        var done = kit.lifecycle.approve(kit.newAdmin(), held.id());
        assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(kit.balance(a.id())).isEqualTo(75_000);
        assertThat(kit.balance(b.id())).isEqualTo(25_000);
        assertThat(kit.history.history(held.id())).extracting(h -> h.to().name()).containsExactly("PENDING_REVIEW", "COMPLETED");
        assertThat(kit.count("SELECT count(*) FROM audit_log WHERE action = 'PAYMENT_APPROVED' AND target_id = ?", held.id().toString())).isEqualTo(1);
        assertThat(kit.ledgerSum("USD")).isZero();
    }

    @Test
    void adminCannotApproveTheirOwnHeldPayment() {
        var adminAndPayer = kit.newAdmin();
        var a = kit.fundedAccount(adminAndPayer, "USD", "1000.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var held = kit.paymentService.transfer(adminAndPayer, key(), new TransferCommand(a.id(), b.id(), Money.parse("250.00", "USD"), null)).payment();

        assertThatThrownBy(() -> kit.lifecycle.approve(adminAndPayer, held.id()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SELF_APPROVAL_NOT_ALLOWED");
        assertThat(kit.payments.find(held.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING_REVIEW);
    }

    @Test
    void approvalRechecksFundsAndFailsIfBalanceDroppedWhileWaiting() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "300.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var held = kit.paymentService.transfer(alice, key(), new TransferCommand(a.id(), b.id(), Money.parse("250.00", "USD"), null)).payment();
        // alice spends most of her money on a small payment (below review threshold) while the big one waits
        kit.paymentService.transfer(alice, key(), new TransferCommand(a.id(), b.id(), Money.parse("90.00", "USD"), null));

        var after = kit.lifecycle.approve(kit.newAdmin(), held.id());
        assertThat(after.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(after.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(kit.balance(a.id())).isEqualTo(21_000);
    }

    @Test
    void rejectedReviewMovesNothingAndCannotBeApprovedAfterwards() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "1000.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var held = kit.paymentService.transfer(alice, key(), new TransferCommand(a.id(), b.id(), Money.parse("250.00", "USD"), null)).payment();
        var admin = kit.newAdmin();

        assertThat(kit.lifecycle.reject(admin, held.id(), "suspicious").status()).isEqualTo(PaymentStatus.REJECTED);
        assertThatThrownBy(() -> kit.lifecycle.approve(admin, held.id()))
                .extracting("code").isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(kit.balance(a.id())).isEqualTo(100_000);
    }

    @Test
    void nonAdminsCannotUseAdminOperations() {
        var user = kit.newUser();
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> kit.lifecycle.approve(user, id)).extracting("code").isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> kit.lifecycle.reject(user, id, "x")).extracting("code").isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> kit.lifecycle.reverse(user, id, "x")).extracting("code").isEqualTo("FORBIDDEN");
    }

    // -------------------------------------------------------------------------------------------- risk

    @Test
    void singlePaymentOverTheLimitIsBlockedAndRecordedWithTheRuleThatFired() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "9000.00");
        var b = kit.openAccount(kit.newUser(), "USD");

        var p = kit.paymentService.transfer(alice, key(), new TransferCommand(a.id(), b.id(), Money.parse("5000.01", "USD"), null)).payment();
        assertThat(p.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(p.failureReason()).isEqualTo("RISK_BLOCKED");
        assertThat(kit.balance(a.id())).isEqualTo(900_000);
        var hits = kit.jdbc.sql("SELECT hits::text FROM risk_decisions WHERE payment_id = :p").param("p", p.id()).query(String.class).single();
        assertThat(hits).contains("MAX_TRANSACTION").contains("BLOCK");
    }

    @Test
    void ordinaryPaymentIsAllowedAndStillGetsAnAuditableDecision() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "100.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var p = kit.paymentService.transfer(alice, key(), new TransferCommand(a.id(), b.id(), Money.parse("5.00", "USD"), null)).payment();
        assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(kit.count("SELECT count(*) FROM risk_decisions WHERE payment_id = ? AND decision = 'ALLOW'", p.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ reversal

    @Test
    void reversalPostsACompensatingEntryAndNeverEditsTheOriginal() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var p = kit.paymentService.transfer(alice, key(), new TransferCommand(a.id(), b.id(), Money.parse("20.00", "USD"), null)).payment();
        var originalEntry = kit.ledger.findEntry(p.id(), LedgerRepository.EntryKind.TRANSFER).orElseThrow();

        var reversed = kit.lifecycle.reverse(kit.newAdmin(), p.id(), "customer dispute");

        assertThat(reversed.status()).isEqualTo(PaymentStatus.REVERSED);
        assertThat(kit.balance(a.id())).isEqualTo(5_000);
        assertThat(kit.balance(b.id())).isZero();
        var compensation = kit.ledger.findEntry(p.id(), LedgerRepository.EntryKind.REVERSAL).orElseThrow();
        assertThat(kit.jdbc.sql("SELECT reverses_entry_id FROM journal_entries WHERE id = :i").param("i", compensation.id())
                .query(UUID.class).single()).isEqualTo(originalEntry.id());
        assertThat(kit.ledger.legsOf(originalEntry.id())).isEqualTo(originalEntry.legs()); // original unchanged
        assertThat(kit.ledgerSum("USD")).isZero();
        assertThatThrownBy(() -> kit.lifecycle.reverse(kit.newAdmin(), p.id(), "again"))
                .extracting("code").isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void reversalIsRefusedWhenThePayeeHasAlreadySpentTheMoneyAndNothingChanges() {
        var alice = kit.newUser();
        var bob = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var b = kit.openAccount(bob, "USD");
        var carol = kit.openAccount(kit.newUser(), "USD");
        var p = kit.paymentService.transfer(alice, key(), new TransferCommand(a.id(), b.id(), Money.parse("20.00", "USD"), null)).payment();
        kit.paymentService.transfer(bob, key(), new TransferCommand(b.id(), carol.id(), Money.parse("15.00", "USD"), null));

        assertThatThrownBy(() -> kit.lifecycle.reverse(kit.newAdmin(), p.id(), "dispute"))
                .extracting("code").isEqualTo("INSUFFICIENT_FUNDS_FOR_REVERSAL");
        assertThat(kit.payments.find(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(kit.balance(b.id())).isEqualTo(500);
    }

    // -------------------------------------------------------------------------------------------- payouts

    @Test
    void payoutIsInFlightNotSettledUntilTheRailConfirms() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "100.00");
        long clearingBefore = kit.systemBalance(AccountType.PAYOUT_CLEARING, "USD");

        var p = kit.paymentService.payout(alice, key(), new PayoutCommand(a.id(), "bank:test-0001", Money.parse("40.00", "USD"), null)).payment();

        assertThat(p.status()).isEqualTo(PaymentStatus.SUBMITTED);                 // NOT settled
        assertThat(kit.balance(a.id())).isEqualTo(6_000);
        assertThat(kit.systemBalance(AccountType.PAYOUT_CLEARING, "USD")).isEqualTo(clearingBefore + 4_000);
        long settledBefore = kit.systemBalance(AccountType.EXTERNAL_SETTLED, "USD");

        var settled = kit.lifecycle.railSettled(p.id(), "rail-ref-1");
        assertThat(settled.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(kit.systemBalance(AccountType.PAYOUT_CLEARING, "USD")).isEqualTo(clearingBefore);
        assertThat(kit.systemBalance(AccountType.EXTERNAL_SETTLED, "USD")).isEqualTo(settledBefore + 4_000);
        assertThat(kit.ledgerSum("USD")).isZero();
    }

    @Test
    void railConfirmationIsIdempotent() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "100.00");
        var p = kit.paymentService.payout(alice, key(), new PayoutCommand(a.id(), "bank:test-0002", Money.parse("10.00", "USD"), null)).payment();
        long before = kit.systemBalance(AccountType.EXTERNAL_SETTLED, "USD");

        kit.lifecycle.railSettled(p.id(), "r");
        kit.lifecycle.railSettled(p.id(), "r");
        kit.lifecycle.railSettled(p.id(), "r");

        assertThat(kit.systemBalance(AccountType.EXTERNAL_SETTLED, "USD")).isEqualTo(before + 1_000);
        assertThat(kit.count("SELECT count(*) FROM journal_entries WHERE payment_id = ? AND kind = 'PAYOUT_SETTLE'", p.id())).isEqualTo(1);
    }

    @Test
    void railRejectionReturnsFundsWithACompensatingEntry() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "100.00");
        var p = kit.paymentService.payout(alice, key(), new PayoutCommand(a.id(), "bank:test-0003", Money.parse("60.00", "USD"), null)).payment();
        assertThat(kit.balance(a.id())).isEqualTo(4_000);

        var returned = kit.lifecycle.railRejected(p.id(), "RAIL_REJECTED");
        kit.lifecycle.railRejected(p.id(), "RAIL_REJECTED"); // duplicate callback: no extra effect

        assertThat(returned.status()).isEqualTo(PaymentStatus.REVERSED);
        assertThat(kit.balance(a.id())).isEqualTo(10_000);
        assertThat(kit.count("SELECT count(*) FROM journal_entries WHERE payment_id = ? AND kind = 'PAYOUT_RETURN'", p.id())).isEqualTo(1);
        assertThat(kit.ledgerSum("USD")).isZero();
    }

    @Test
    void settledPayoutCannotBeReturnedAndRejectedPayoutCannotBeSettled() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "100.00");
        var settled = kit.paymentService.payout(alice, key(), new PayoutCommand(a.id(), "bank:test-0004", Money.parse("5.00", "USD"), null)).payment();
        var rejected = kit.paymentService.payout(alice, key(), new PayoutCommand(a.id(), "bank:test-0005", Money.parse("5.00", "USD"), null)).payment();
        kit.lifecycle.railSettled(settled.id(), "r");
        kit.lifecycle.railRejected(rejected.id(), "x");

        assertThatThrownBy(() -> kit.lifecycle.railRejected(settled.id(), "late"))
                .extracting("code").isEqualTo("INVALID_STATE_TRANSITION");
        assertThatThrownBy(() -> kit.lifecycle.railSettled(rejected.id(), "late"))
                .extracting("code").isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(kit.balance(a.id())).isEqualTo(9_500); // 100.00 - 5.00 settled; the rejected 5.00 was returned
    }

    @Test
    void payoutInsufficientFundsAndInvalidDestinationAreRejectedBeforeAnyMovement() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "10.00");
        var p = kit.paymentService.payout(alice, key(), new PayoutCommand(a.id(), "bank:test-0006", Money.parse("10.01", "USD"), null)).payment();
        assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
        for (var bad : new String[] {null, "", "  ", "x'; DROP TABLE payments;--", "line\nbreak", "x".repeat(141)}) {
            assertThatThrownBy(() -> kit.paymentService.payout(alice, key(), new PayoutCommand(a.id(), bad, Money.parse("1.00", "USD"), null)))
                    .extracting("code").isEqualTo("INVALID_DESTINATION");
        }
        assertThat(kit.balance(a.id())).isEqualTo(1_000);
    }

    @Test
    void payoutReplayDoesNotDebitTwice() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "100.00");
        var k = key();
        var cmd = new PayoutCommand(a.id(), "bank:test-0007", Money.parse("30.00", "USD"), null);
        var first = kit.paymentService.payout(alice, k, cmd);
        var second = kit.paymentService.payout(alice, k, cmd);
        assertThat(second.replayed()).isTrue();
        assertThat(second.payment().id()).isEqualTo(first.payment().id());
        assertThat(kit.balance(a.id())).isEqualTo(7_000);
    }
}
