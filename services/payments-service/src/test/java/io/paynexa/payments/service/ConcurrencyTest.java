package io.paynexa.payments.service;

import io.paynexa.payments.domain.DomainException;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.domain.PaymentStatus;
import io.paynexa.payments.service.PaymentService.TransferCommand;
import io.paynexa.payments.support.TestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real threads, real connections, real row locks. Every test releases all threads at the same instant to maximise
 * contention, then checks the financial invariants rather than just "no exception".
 */
class ConcurrencyTest {

    static TestKit kit;

    @BeforeAll
    static void start() {
        kit = TestKit.create();
    }

    @AfterAll
    static void stop() {
        kit.close();
    }

    private static double retries() {
        var c = kit.meters.find("paynexa.payments.transient_db_retries").counter();
        return c == null ? 0 : c.count();
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    /** Runs all tasks simultaneously and returns results or the thrown exception per task. */
    static <T> List<Object> race(List<Callable<T>> tasks) throws Exception {
        var pool = Executors.newFixedThreadPool(tasks.size());
        var ready = new CountDownLatch(tasks.size());
        var go = new CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<Object>>();
        for (var t : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return t.call();
                } catch (Throwable e) {
                    return e;
                }
            }));
        }
        ready.await();
        go.countDown();
        var out = new ArrayList<Object>();
        for (var f : futures) {
            out.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return out;
    }

    @RepeatedTest(3)
    void concurrentTransfersCanNeverOverspendTheBalance() throws Exception {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "100.00");   // exactly 10 x 10.00
        var b = kit.openAccount(kit.newUser(), "USD");

        var tasks = new ArrayList<Callable<PaymentService.Result>>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> kit.paymentService.transfer(alice, key(),
                    new TransferCommand(a.id(), b.id(), Money.parse("10.00", "USD"), null)));
        }
        var results = race(tasks);

        long completed = results.stream().filter(r -> r instanceof PaymentService.Result pr
                && pr.payment().status() == PaymentStatus.COMPLETED).count();
        long failed = results.stream().filter(r -> r instanceof PaymentService.Result pr
                && pr.payment().status() == PaymentStatus.FAILED).count();
        assertThat(results).noneMatch(r -> r instanceof Throwable);
        assertThat(completed).isEqualTo(10);
        assertThat(failed).isEqualTo(20);
        assertThat(kit.balance(a.id())).isZero();
        assertThat(kit.balance(b.id())).isEqualTo(10_000);
        assertThat(kit.ledger.ledgerBalance(a.id())).isZero();
        assertThat(kit.ledgerSum("USD")).isZero();
    }

    @RepeatedTest(3)
    void concurrentIdenticalRequestsWithOneKeyCreateExactlyOnePayment() throws Exception {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "100.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var sharedKey = key();

        var tasks = new ArrayList<Callable<PaymentService.Result>>();
        for (int i = 0; i < 25; i++) {
            tasks.add(() -> kit.paymentService.transfer(alice, sharedKey,
                    new TransferCommand(a.id(), b.id(), Money.parse("40.00", "USD"), "dup")));
        }
        var results = race(tasks);

        assertThat(results).noneMatch(r -> r instanceof Throwable);
        var ids = results.stream().map(r -> ((PaymentService.Result) r).payment().id()).distinct().toList();
        assertThat(ids).hasSize(1);
        assertThat(results.stream().filter(r -> !((PaymentService.Result) r).replayed()).count()).isEqualTo(1);
        assertThat(kit.balance(a.id())).isEqualTo(6_000);
        assertThat(kit.balance(b.id())).isEqualTo(4_000);
        assertThat(kit.count("SELECT count(*) FROM payments WHERE payer_account_id = ? AND kind = 'TRANSFER'", a.id())).isEqualTo(1);
        assertThat(kit.count("SELECT count(*) FROM journal_entries WHERE payment_id = ?", ids.get(0))).isEqualTo(1);
        assertThat(kit.count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", ids.get(0))).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithOneKeyButDifferentBodiesNeverBothSucceed() throws Exception {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "100.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var sharedKey = key();

        var tasks = new ArrayList<Callable<PaymentService.Result>>();
        for (int i = 1; i <= 10; i++) {
            var amount = i + ".00";
            tasks.add(() -> kit.paymentService.transfer(alice, sharedKey,
                    new TransferCommand(a.id(), b.id(), Money.parse(amount, "USD"), null)));
        }
        var results = race(tasks);

        long winners = results.stream().filter(r -> r instanceof PaymentService.Result pr && !pr.replayed()).count();
        long conflicts = results.stream().filter(r -> r instanceof DomainException de
                && de.code().equals("IDEMPOTENCY_KEY_REUSED")).count();
        assertThat(winners).isEqualTo(1);
        assertThat(conflicts).isEqualTo(9);
        assertThat(kit.count("SELECT count(*) FROM payments WHERE payer_account_id = ?", a.id())).isEqualTo(1);
        assertThat(kit.ledgerSum("USD")).isZero();
    }

    @RepeatedTest(3)
    void opposingTransfersBetweenTheSameAccountsDoNotDeadlock() throws Exception {
        var alice = kit.newUser();
        var bob = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "1000.00");
        var b = kit.fundedAccount(bob, "USD", "1000.00");

        double retriesBefore = retries();
        var tasks = new ArrayList<Callable<PaymentService.Result>>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> kit.paymentService.transfer(alice, key(),
                    new TransferCommand(a.id(), b.id(), Money.parse("1.00", "USD"), null)));
            tasks.add(() -> kit.paymentService.transfer(bob, key(),
                    new TransferCommand(b.id(), a.id(), Money.parse("1.00", "USD"), null)));
        }
        var results = race(tasks);

        assertThat(results).as("no deadlock victim or other error").noneMatch(r -> r instanceof Throwable);
        // The service retries transient DB failures, which would hide a deadlock. Zero retries proves none occurred.
        assertThat(retries()).as("transient DB retries (deadlock victims) during opposing transfers").isEqualTo(retriesBefore);
        assertThat(kit.balance(a.id()) + kit.balance(b.id())).isEqualTo(200_000);
        assertThat(kit.balance(a.id())).isEqualTo(100_000);   // 20 out, 20 in, same amounts
        assertThat(kit.ledgerSum("USD")).isZero();
    }

    @Test
    void manyPayersIntoOnePayeeKeepsEveryBalanceExact() throws Exception {
        var payee = kit.openAccount(kit.newUser(), "USD");
        var tasks = new ArrayList<Callable<PaymentService.Result>>();
        for (int i = 0; i < 25; i++) {
            var user = kit.newUser();
            var acc = kit.fundedAccount(user, "USD", "10.00");
            tasks.add(() -> kit.paymentService.transfer(user, key(),
                    new TransferCommand(acc.id(), payee.id(), Money.parse("10.00", "USD"), null)));
        }
        var results = race(tasks);
        assertThat(results).noneMatch(r -> r instanceof Throwable);
        assertThat(kit.balance(payee.id())).isEqualTo(25 * 1_000);
        assertThat(kit.ledger.ledgerBalance(payee.id())).isEqualTo(25 * 1_000);
    }

    @Test
    void racingReversalsOfOnePaymentApplyExactlyOnce() throws Exception {
        var alice = kit.newUser();
        var admin = kit.newAdmin();
        var a = kit.fundedAccount(alice, "USD", "50.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var p = kit.paymentService.transfer(alice, key(), new TransferCommand(a.id(), b.id(), Money.parse("20.00", "USD"), null)).payment();

        var tasks = new ArrayList<Callable<io.paynexa.payments.domain.Payment>>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> kit.lifecycle.reverse(admin, p.id(), "dispute"));
        }
        var results = race(tasks);

        long ok = results.stream().filter(r -> r instanceof io.paynexa.payments.domain.Payment).count();
        long rejected = results.stream().filter(r -> r instanceof DomainException de
                && de.code().equals("INVALID_STATE_TRANSITION")).count();
        assertThat(ok).isEqualTo(1);
        assertThat(rejected).isEqualTo(9);
        assertThat(kit.balance(a.id())).isEqualTo(5_000);
        assertThat(kit.balance(b.id())).isZero();
        assertThat(kit.count("SELECT count(*) FROM journal_entries WHERE payment_id = ? AND kind = 'REVERSAL'", p.id())).isEqualTo(1);
        assertThat(kit.ledgerSum("USD")).isZero();
    }
}
