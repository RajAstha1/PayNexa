package io.paynexa.payments.service;

import io.paynexa.payments.domain.AccountStatus;
import io.paynexa.payments.domain.DomainException;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.domain.PaymentStatus;
import io.paynexa.payments.service.PaymentService.TransferCommand;
import io.paynexa.payments.support.TestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferServiceTest {

    static TestKit kit;

    @BeforeAll
    static void start() {
        kit = TestKit.create();
    }

    @AfterAll
    static void stop() {
        kit.close();
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static TransferCommand cmd(UUID from, UUID to, String amount) {
        return new TransferCommand(from, to, Money.parse(amount, "USD"), "test");
    }

    @Test
    void successfulTransferMovesExactAmountWithBalancedLedgerEntry() {
        var alice = kit.newUser();
        var bob = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "100.00");
        var b = kit.openAccount(bob, "USD");

        var result = kit.paymentService.transfer(alice, key(), cmd(a.id(), b.id(), "30.25"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(kit.balance(a.id())).isEqualTo(6_975);
        assertThat(kit.balance(b.id())).isEqualTo(3_025);
        var entry = kit.ledger.findEntry(result.payment().id(), io.paynexa.payments.persistence.LedgerRepository.EntryKind.TRANSFER).orElseThrow();
        assertThat(entry.legs()).hasSize(2);
        assertThat(entry.legs()).extracting(l -> l.direction().name()).containsExactly("DEBIT", "CREDIT");
        // stored projection equals the ledger
        assertThat(kit.ledger.ledgerBalance(a.id())).isEqualTo(kit.balance(a.id()));
        assertThat(kit.ledger.ledgerBalance(b.id())).isEqualTo(kit.balance(b.id()));
        assertThat(kit.ledgerSum("USD")).isZero();
    }

    @Test
    void transferWritesHistoryAndOutboxEventAtomically() {
        var a = kit.fundedAccount(kit.newUser(), "USD", "10");
        var payer = kit.accounts.find(a.id()).orElseThrow();
        var b = kit.openAccount(kit.newUser(), "USD");
        var actor = io.paynexa.payments.service.Actor.user(payer.ownerUserId(), false);

        var p = kit.paymentService.transfer(actor, key(), cmd(a.id(), b.id(), "1.00")).payment();

        assertThat(kit.history.history(p.id())).extracting(h -> h.to().name()).containsExactly("COMPLETED");
        assertThat(kit.count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'payment.completed' AND status = 'PENDING'", p.id())).isEqualTo(1);
    }

    @Test
    void insufficientFundsIsACommittedFailureAndMovesNothing() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "5.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var k = key();

        var r1 = kit.paymentService.transfer(alice, k, cmd(a.id(), b.id(), "5.01"));
        assertThat(r1.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(r1.payment().failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(kit.balance(a.id())).isEqualTo(500);
        assertThat(kit.balance(b.id())).isZero();

        // Retry with the same key replays the same failed outcome even after the account is funded.
        kit.paymentService.fund(kit.newAdmin(), key(), a.id(), Money.parse("100", "USD"));
        var r2 = kit.paymentService.transfer(alice, k, cmd(a.id(), b.id(), "5.01"));
        assertThat(r2.replayed()).isTrue();
        assertThat(r2.payment().id()).isEqualTo(r1.payment().id());
        assertThat(kit.balance(b.id())).isZero();
    }

    @Test
    void exactBalanceCanBeSpentToZeroButNotOneCentMore() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "10.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        assertThat(kit.paymentService.transfer(alice, key(), cmd(a.id(), b.id(), "10.00")).payment().status())
                .isEqualTo(PaymentStatus.COMPLETED);
        assertThat(kit.balance(a.id())).isZero();
        assertThat(kit.paymentService.transfer(alice, key(), cmd(a.id(), b.id(), "0.01")).payment().status())
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void sameKeySameRequestReplaysWithoutSecondFinancialEffect() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var k = key();

        var first = kit.paymentService.transfer(alice, k, cmd(a.id(), b.id(), "20.00"));
        var second = kit.paymentService.transfer(alice, k, cmd(a.id(), b.id(), "20.00"));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.payment().id()).isEqualTo(first.payment().id());
        assertThat(kit.balance(a.id())).isEqualTo(3_000);
        assertThat(kit.balance(b.id())).isEqualTo(2_000);
        assertThat(kit.count("SELECT count(*) FROM journal_entries WHERE payment_id = ?", first.payment().id())).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentRequestIsRejectedAndChangesNothing() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var k = key();
        kit.paymentService.transfer(alice, k, cmd(a.id(), b.id(), "20.00"));

        assertThatThrownBy(() -> kit.paymentService.transfer(alice, k, cmd(a.id(), b.id(), "21.00")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(kit.balance(b.id())).isEqualTo(2_000);
    }

    @Test
    void idempotencyKeysAreScopedPerUser() {
        var alice = kit.newUser();
        var carol = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var c = kit.fundedAccount(carol, "USD", "50.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var shared = key();

        var r1 = kit.paymentService.transfer(alice, shared, cmd(a.id(), b.id(), "5.00"));
        var r2 = kit.paymentService.transfer(carol, shared, cmd(c.id(), b.id(), "7.00"));

        assertThat(r2.replayed()).isFalse();
        assertThat(r2.payment().id()).isNotEqualTo(r1.payment().id());
        assertThat(kit.balance(b.id())).isEqualTo(1_200);
    }

    @Test
    void expiredIdempotencyKeyCanBeReusedAsANewRequest() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var k = key();
        var first = kit.paymentService.transfer(alice, k, cmd(a.id(), b.id(), "1.00"));

        kit.clock.advance(java.time.Duration.ofDays(8)); // retention is 7 days
        var again = kit.paymentService.transfer(alice, k, cmd(a.id(), b.id(), "2.00"));

        assertThat(again.replayed()).isFalse();
        assertThat(again.payment().id()).isNotEqualTo(first.payment().id());
        assertThat(kit.balance(b.id())).isEqualTo(300);
    }

    @Test
    void cannotSpendFromSomeoneElsesAccountAndItLooksLikeMissing() {
        var alice = kit.newUser();
        var mallory = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var m = kit.openAccount(mallory, "USD");

        assertThatThrownBy(() -> kit.paymentService.transfer(mallory, key(), cmd(a.id(), m.id(), "10.00")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ACCOUNT_NOT_FOUND");
        assertThatThrownBy(() -> kit.paymentService.transfer(mallory, key(), cmd(UUID.randomUUID(), m.id(), "10.00")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(kit.balance(a.id())).isEqualTo(5_000);
    }

    @Test
    void rejectsUnknownPayeeSameAccountAndCurrencyMismatch() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var eur = kit.openAccount(kit.newUser(), "EUR");

        assertThatThrownBy(() -> kit.paymentService.transfer(alice, key(), cmd(a.id(), UUID.randomUUID(), "1.00")))
                .extracting("code").isEqualTo("PAYEE_NOT_FOUND");
        assertThatThrownBy(() -> kit.paymentService.transfer(alice, key(), cmd(a.id(), a.id(), "1.00")))
                .extracting("code").isEqualTo("SAME_ACCOUNT");
        assertThatThrownBy(() -> kit.paymentService.transfer(alice, key(), cmd(a.id(), eur.id(), "1.00")))
                .extracting("code").isEqualTo("CURRENCY_MISMATCH");
        assertThatThrownBy(() -> kit.paymentService.transfer(alice, key(),
                new TransferCommand(a.id(), eur.id(), Money.parse("1", "EUR"), null)))
                .extracting("code").isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void cannotTransferToSystemAccounts() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var clearing = kit.accounts.systemAccount(io.paynexa.payments.domain.AccountType.PAYOUT_CLEARING, "USD");
        assertThatThrownBy(() -> kit.paymentService.transfer(alice, key(), cmd(a.id(), clearing.id(), "1.00")))
                .extracting("code").isEqualTo("PAYEE_NOT_FOUND");
    }

    @Test
    void requestErrorsRollBackIncludingTheIdempotencyClaim() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var k = key();
        var ghost = UUID.randomUUID();
        assertThatThrownBy(() -> kit.paymentService.transfer(alice, k, cmd(a.id(), ghost, "1.00")))
                .extracting("code").isEqualTo("PAYEE_NOT_FOUND");
        assertThat(kit.idempotency.find(alice.userId(), k)).isEmpty();

        // The same key is immediately usable for a corrected request.
        var b = kit.openAccount(kit.newUser(), "USD");
        assertThat(kit.paymentService.transfer(alice, k, cmd(a.id(), b.id(), "1.00")).payment().status())
                .isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void frozenPayerOrPayeeFailsWithoutMovingMoney() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var admin = kit.newAdmin();

        kit.accountService.setStatus(admin, b.id(), AccountStatus.FROZEN, "investigation");
        var toFrozen = kit.paymentService.transfer(alice, key(), cmd(a.id(), b.id(), "1.00")).payment();
        assertThat(toFrozen.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(toFrozen.failureReason()).isEqualTo("PAYEE_UNAVAILABLE");

        kit.accountService.setStatus(admin, b.id(), AccountStatus.ACTIVE, "cleared");
        kit.accountService.setStatus(admin, a.id(), AccountStatus.FROZEN, "investigation");
        var fromFrozen = kit.paymentService.transfer(alice, key(), cmd(a.id(), b.id(), "1.00")).payment();
        assertThat(fromFrozen.failureReason()).isEqualTo("ACCOUNT_FROZEN");
        assertThat(kit.balance(a.id())).isEqualTo(5_000);
        assertThat(kit.balance(b.id())).isZero();
    }

    @Test
    void invalidIdempotencyKeysAreRejectedBeforeAnyWork() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        for (var bad : new String[] {null, "", "short", "has space in key", "x".repeat(65), "bad/slash-key"}) {
            assertThatThrownBy(() -> kit.paymentService.transfer(alice, bad, cmd(a.id(), b.id(), "1.00")))
                    .extracting("code").isEqualTo("INVALID_IDEMPOTENCY_KEY");
        }
    }

    @Test
    void sandboxFundingRequiresAdminAndTheFlag() {
        var user = kit.newUser();
        var a = kit.openAccount(user, "USD");
        assertThatThrownBy(() -> kit.paymentService.fund(user, key(), a.id(), Money.parse("1", "USD")))
                .extracting("code").isEqualTo("FORBIDDEN");
    }

    @Test
    void fundingKeepsTheGlobalLedgerAtZeroAndTracksIssuedMoney() {
        var a = kit.fundedAccount(kit.newUser(), "GBP", "12.50");
        assertThat(kit.balance(a.id())).isEqualTo(1_250);
        assertThat(kit.ledgerSum("GBP")).isZero();
        assertThat(kit.systemBalance(io.paynexa.payments.domain.AccountType.FUNDING_SOURCE, "GBP")).isEqualTo(-1_250);
    }
}
