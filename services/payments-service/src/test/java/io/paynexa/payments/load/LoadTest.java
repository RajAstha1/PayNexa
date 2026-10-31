package io.paynexa.payments.load;

import io.paynexa.payments.domain.AccountType;
import io.paynexa.payments.domain.DomainException;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.domain.PaymentStatus;
import io.paynexa.payments.outbox.OutboxRelay;
import io.paynexa.payments.rail.PayoutWorker;
import io.paynexa.payments.rail.SimulatedRailClient;
import io.paynexa.payments.recon.ReconciliationService;
import io.paynexa.payments.service.Actor;
import io.paynexa.payments.service.PaymentService.PayoutCommand;
import io.paynexa.payments.service.PaymentService.TransferCommand;
import io.paynexa.payments.support.RecordingPublisher;
import io.paynexa.payments.support.TestKit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in (./gradlew :services:payments-service:loadTest). A contended mixed workload against real PostgreSQL, followed
 * by invariant checks. The numbers printed describe THIS machine and THIS embedded database only; they are a
 * regression signal and a correctness stress test, not a capacity claim for any deployment.
 */
@Tag("load")
class LoadTest {

    static final int ACCOUNTS = 200;
    static final int THREADS = 32;
    static final int OPERATIONS = 20_000;
    static final long START_BALANCE_MINOR = 1_000_00;   // 1000.00 each

    @Test
    void mixedContendedWorkloadKeepsEveryFinancialInvariant() throws Exception {
        try (var kit = TestKit.create()) {
            var owners = new ArrayList<Actor>();
            var accountIds = new ArrayList<UUID>();
            for (int i = 0; i < ACCOUNTS; i++) {
                var owner = kit.newUser();
                owners.add(owner);
                accountIds.add(kit.fundedAccount(owner, "USD", "1000.00").id());
            }
            long totalBefore = kit.count("SELECT COALESCE(SUM(balance_minor),0) FROM accounts WHERE type = 'CUSTOMER'");
            assertThat(totalBefore).isEqualTo(ACCOUNTS * START_BALANCE_MINOR);

            var latenciesNanos = new long[OPERATIONS];
            var next = new AtomicInteger();
            var completed = new AtomicInteger();
            var failedBusiness = new AtomicInteger();
            var replays = new AtomicInteger();
            var payouts = new AtomicInteger();
            var errors = new AtomicInteger();
            var firstError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var duplicateKeyPool = new String[500];
            for (int i = 0; i < duplicateKeyPool.length; i++) {
                duplicateKeyPool[i] = "dup-" + UUID.randomUUID();
            }
            var duplicateOwner = 7;
            var duplicateFrom = accountIds.get(duplicateOwner);
            var duplicateTo = accountIds.get(8);

            var pool = Executors.newFixedThreadPool(THREADS);
            var go = new CountDownLatch(1);
            var done = new CountDownLatch(THREADS);
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    try {
                        go.await();
                        int i;
                        while ((i = next.getAndIncrement()) < OPERATIONS) {
                            var rnd = ThreadLocalRandom.current();
                            long start = System.nanoTime();
                            try {
                                int roll = rnd.nextInt(100);
                                if (roll < 55) {                                   // random transfer between random accounts
                                    int from = rnd.nextInt(ACCOUNTS);
                                    int to = (from + 1 + rnd.nextInt(ACCOUNTS - 1)) % ACCOUNTS;
                                    var r = kit.paymentService.transfer(owners.get(from), "k-" + UUID.randomUUID(),
                                            new TransferCommand(accountIds.get(from), accountIds.get(to), Money.parse(amount(rnd), "USD"), null));
                                    count(r.payment().status(), completed, failedBusiness);
                                } else if (roll < 75) {                            // hot payee: everybody pays account 0
                                    int from = 1 + rnd.nextInt(ACCOUNTS - 1);
                                    var r = kit.paymentService.transfer(owners.get(from), "k-" + UUID.randomUUID(),
                                            new TransferCommand(accountIds.get(from), accountIds.get(0), Money.parse(amount(rnd), "USD"), null));
                                    count(r.payment().status(), completed, failedBusiness);
                                } else if (roll < 85) {                            // opposing pair, deadlock bait
                                    boolean ab = rnd.nextBoolean();
                                    int from = ab ? 10 : 11;
                                    int to = ab ? 11 : 10;
                                    var r = kit.paymentService.transfer(owners.get(from), "k-" + UUID.randomUUID(),
                                            new TransferCommand(accountIds.get(from), accountIds.get(to), Money.parse("1.00", "USD"), null));
                                    count(r.payment().status(), completed, failedBusiness);
                                } else if (roll < 95) {                            // client retries with a shared key set
                                    var key = duplicateKeyPool[rnd.nextInt(duplicateKeyPool.length)];
                                    var r = kit.paymentService.transfer(owners.get(duplicateOwner), key,
                                            new TransferCommand(duplicateFrom, duplicateTo, Money.parse("0.50", "USD"), "dup"));
                                    if (r.replayed()) replays.incrementAndGet(); else count(r.payment().status(), completed, failedBusiness);
                                } else {                                           // payouts through the rail
                                    int from = rnd.nextInt(ACCOUNTS);
                                    kit.paymentService.payout(owners.get(from), "k-" + UUID.randomUUID(),
                                            new PayoutCommand(accountIds.get(from), rnd.nextInt(4) == 0 ? "bank:load-reject" : "bank:load-ok",
                                                    Money.parse("2.00", "USD"), null));
                                    payouts.incrementAndGet();
                                }
                            } catch (DomainException expected) {
                                failedBusiness.incrementAndGet();
                            } catch (Throwable e) {
                                errors.incrementAndGet();
                                firstError.compareAndSet(null, e);
                            }
                            latenciesNanos[i] = System.nanoTime() - start;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            long wallStart = System.nanoTime();
            go.countDown();
            assertThat(done.await(10, TimeUnit.MINUTES)).as("workload finished in time").isTrue();
            double seconds = (System.nanoTime() - wallStart) / 1e9;
            pool.shutdown();

            // Settle the rail and publish everything, as the background workers would.
            var worker = new PayoutWorker(kit.tx, kit.payments, kit.lifecycle, new SimulatedRailClient(), kit.props, kit.clock, kit.meters);
            while (worker.runOnce() > 0) {
                // keep draining
            }
            var relay = new OutboxRelay(kit.tx, kit.outbox, new RecordingPublisher(), kit.props, kit.clock, kit.meters);
            long published = 0;
            int n;
            while ((n = relay.runOnce()) > 0) {
                published += n;
            }

            // ------------------------------------------------------------------ report (measured, this machine only)
            Arrays.sort(latenciesNanos);
            System.out.printf("%n==== LOAD TEST RESULT (embedded PostgreSQL 17, %d threads, %d accounts) ====%n", THREADS, ACCOUNTS);
            System.out.printf("operations: %d in %.1fs = %.0f ops/s%n", OPERATIONS, seconds, OPERATIONS / seconds);
            System.out.printf("latency ms: p50=%.1f p95=%.1f p99=%.1f max=%.1f%n", pct(latenciesNanos, 50), pct(latenciesNanos, 95),
                    pct(latenciesNanos, 99), latenciesNanos[latenciesNanos.length - 1] / 1e6);
            System.out.printf("outcomes: completed=%d business-failed=%d idempotent-replays=%d payouts=%d unexpected-errors=%d%n",
                    completed.get(), failedBusiness.get(), replays.get(), payouts.get(), errors.get());
            System.out.printf("transient DB retries: %.0f   outbox events published: %d%n",
                    counter(kit, "paynexa.payments.transient_db_retries"), published);

            // ------------------------------------------------------------------ invariants (hard assertions)
            assertThat(errors.get()).as("unexpected exceptions, first: %s", firstError.get()).isZero();
            assertThat(kit.ledgerSum("USD")).as("ledger sums to zero").isZero();
            assertThat(kit.count("SELECT count(*) FROM accounts WHERE type = 'CUSTOMER' AND balance_minor < 0")).isZero();
            long totalAfter = kit.count("SELECT COALESCE(SUM(balance_minor),0) FROM accounts WHERE type = 'CUSTOMER'");
            long inClearing = kit.systemBalance(AccountType.PAYOUT_CLEARING, "USD");
            long settled = kit.systemBalance(AccountType.EXTERNAL_SETTLED, "USD");
            assertThat(totalAfter + inClearing + settled).as("money is conserved: customers + in-flight + settled").isEqualTo(totalBefore);
            assertThat(inClearing).as("no payout left in flight after draining the worker").isZero();
            assertThat(kit.count("SELECT count(*) FROM payments WHERE kind = 'PAYOUT' AND status = 'SUBMITTED'")).isZero();
            assertThat(kit.count("SELECT count(*) FROM (SELECT payment_id, kind FROM journal_entries GROUP BY 1, 2 HAVING count(*) > 1) d"))
                    .as("no duplicated financial effect").isZero();
            // every duplicate-key request produced at most one payment per distinct key
            assertThat(kit.count("SELECT count(*) FROM (SELECT user_id, idem_key FROM idempotency_keys GROUP BY 1, 2 HAVING count(*) > 1) d")).isZero();

            var recon = new ReconciliationService(kit.jdbc, kit.tx, kit.props, kit.outbox, kit.clock, kit.meters).run("LOAD");
            assertThat(recon.findings()).as("reconciliation after load: %s", recon.findings()).isEmpty();
            assertThat(kit.count("SELECT count(*) FROM payments WHERE status = '" + PaymentStatus.PENDING_REVIEW + "'")).isGreaterThanOrEqualTo(0);
            assertThat(kit.outbox.count("PENDING")).as("everything published").isZero();
        }
    }

    private static String amount(ThreadLocalRandom rnd) {
        return (1 + rnd.nextInt(30)) + "." + "%02d".formatted(rnd.nextInt(100));
    }

    private static void count(PaymentStatus s, AtomicInteger completed, AtomicInteger failed) {
        if (s == PaymentStatus.COMPLETED) completed.incrementAndGet(); else failed.incrementAndGet();
    }

    private static double pct(long[] sortedNanos, int p) {
        int idx = (int) Math.min(sortedNanos.length - 1, Math.ceil(p / 100.0 * sortedNanos.length) - 1);
        return sortedNanos[Math.max(0, idx)] / 1e6;
    }

    private static double counter(TestKit kit, String name) {
        var c = kit.meters.find(name).counter();
        return c == null ? 0 : c.count();
    }
}
