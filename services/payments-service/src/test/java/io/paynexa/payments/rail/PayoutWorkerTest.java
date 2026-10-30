package io.paynexa.payments.rail;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.paynexa.payments.config.PaymentsProperties;
import io.paynexa.payments.domain.AccountType;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.domain.PaymentStatus;
import io.paynexa.payments.service.PaymentService.PayoutCommand;
import io.paynexa.payments.support.TestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutWorkerTest {

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

    private PayoutWorker worker(RailClient rail) {
        return new PayoutWorker(kit.tx, kit.payments, kit.lifecycle, rail, kit.props, kit.clock, kit.meters);
    }

    private io.paynexa.payments.domain.Payment newPayout(String destination, String amount) {
        var user = kit.newUser();
        var acc = kit.fundedAccount(user, "USD", "100.00");
        return kit.paymentService.payout(user, key(), new PayoutCommand(acc.id(), destination, Money.parse(amount, "USD"), null)).payment();
    }

    private PaymentStatus status(UUID id) {
        return kit.payments.find(id).orElseThrow().status();
    }

    @Test
    void settlesWhenTheRailConfirmsAndOnlyThenMovesClearingToExternalSettled() {
        long settledBefore = kit.systemBalance(AccountType.EXTERNAL_SETTLED, "USD");
        var p = newPayout("bank:ok-1", "25.00");
        assertThat(status(p.id())).isEqualTo(PaymentStatus.SUBMITTED);
        assertThat(kit.systemBalance(AccountType.EXTERNAL_SETTLED, "USD")).isEqualTo(settledBefore);

        assertThat(worker(new SimulatedRailClient()).runOnce()).isGreaterThanOrEqualTo(1);

        assertThat(status(p.id())).isEqualTo(PaymentStatus.SETTLED);
        assertThat(kit.systemBalance(AccountType.EXTERNAL_SETTLED, "USD")).isEqualTo(settledBefore + 2_500);
        assertThat(kit.payments.find(p.id()).orElseThrow().railReference()).isEqualTo("sim-" + p.id());
        assertThat(kit.ledgerSum("USD")).isZero();
    }

    @Test
    void rejectionReturnsTheMoneyToTheCustomer() {
        var p = newPayout("bank:acct-reject", "40.00");
        var payerBefore = kit.balance(p.payerAccountId());

        worker(new SimulatedRailClient()).runOnce();

        assertThat(status(p.id())).isEqualTo(PaymentStatus.REVERSED);
        assertThat(kit.balance(p.payerAccountId())).isEqualTo(payerBefore + 4_000);
        assertThat(kit.ledgerSum("USD")).isZero();
    }

    @Test
    void pendingStaysSubmittedWithBackoffAndLaterSettlesWithoutDoublePaying() {
        var p = newPayout("bank:acct-pending", "10.00");
        var rail = new SimulatedRailClient();
        var w = worker(rail);

        w.runOnce();
        assertThat(status(p.id())).isEqualTo(PaymentStatus.SUBMITTED);
        int callsAfterFirst = rail.totalCalls.get();
        w.runOnce();                                        // not due yet: backoff
        assertThat(rail.totalCalls.get()).isEqualTo(callsAfterFirst);

        for (int i = 0; i < 6 && status(p.id()) == PaymentStatus.SUBMITTED; i++) {
            kit.clock.advance(Duration.ofSeconds(10));
            w.runOnce();
        }
        assertThat(status(p.id())).isEqualTo(PaymentStatus.SETTLED);
        assertThat(kit.count("SELECT count(*) FROM journal_entries WHERE payment_id = ? AND kind = 'PAYOUT_SETTLE'", p.id())).isEqualTo(1);
    }

    @Test
    void unreachableRailNeverSettlesOrFailsThePayoutAndRecoversWhenItComesBack() {
        var p = newPayout("bank:acct-down", "10.00");
        var w = worker(new SimulatedRailClient());

        for (int i = 0; i < 4; i++) {
            w.runOnce();
            kit.clock.advance(Duration.ofSeconds(30));
        }
        assertThat(status(p.id())).isEqualTo(PaymentStatus.SUBMITTED);   // never presumed settled
        assertThat(kit.payments.find(p.id()).orElseThrow().railAttempts()).isGreaterThanOrEqualTo(3);
        assertThat(kit.balance(p.payerAccountId())).isEqualTo(9_000);    // money stays in flight

        // the rail now recognises the destination as healthy
        kit.jdbc.sql("UPDATE payments SET destination_ref = 'bank:acct-ok' WHERE id = :id").param("id", p.id()).update();
        kit.clock.advance(Duration.ofHours(1));
        w.runOnce();
        assertThat(status(p.id())).isEqualTo(PaymentStatus.SETTLED);
    }

    @Test
    void crashAfterTheRailPaidIsHealedByTheRailsIdempotencyOnRetry() {
        var p = newPayout("bank:ok-2", "5.00");
        var rail = new SimulatedRailClient();
        // the rail processes the instruction, but our process dies before applying the outcome
        try {
            rail.submit(new RailClient.Instruction(p.id().toString(), "bank:ok-2", 500, "USD"));
        } catch (RailClient.RailUnavailableException e) {
            throw new AssertionError(e);
        }
        assertThat(status(p.id())).isEqualTo(PaymentStatus.SUBMITTED);

        worker(rail).runOnce();                                           // restart: same idempotency key reaches the rail

        assertThat(status(p.id())).isEqualTo(PaymentStatus.SETTLED);
        assertThat(kit.count("SELECT count(*) FROM journal_entries WHERE payment_id = ? AND kind = 'PAYOUT_SETTLE'", p.id())).isEqualTo(1);
    }

    @Test
    void duplicateWorkersNeverApplyTheOutcomeTwice() throws Exception {
        var p = newPayout("bank:ok-3", "5.00");
        var rail = new SimulatedRailClient();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        var go = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> { go.await(); return worker(rail).runOnce(); });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(status(p.id())).isEqualTo(PaymentStatus.SETTLED);
        assertThat(kit.count("SELECT count(*) FROM journal_entries WHERE payment_id = ? AND kind = 'PAYOUT_SETTLE'", p.id())).isEqualTo(1);
        assertThat(kit.ledgerSum("USD")).isZero();
    }

    // ------------------------------------------------------------------------------------ circuit breaker

    private static PaymentsProperties.Rail railConfig(Duration timeout) {
        return new PaymentsProperties.Rail(Duration.ofMillis(50), 10, Duration.ofSeconds(30), Duration.ofMillis(10),
                Duration.ofSeconds(1), timeout, Duration.ofHours(1), 5, false);
    }

    private static CircuitBreaker fastBreaker(Duration openFor) {
        return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50f)
                .waitDurationInOpenState(openFor).permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordExceptions(RailClient.RailUnavailableException.class).build());
    }

    @Test
    void breakerOpensAfterRepeatedFailuresAndThenShortCircuitsWithoutCallingTheRail() {
        var calls = new AtomicInteger();
        RailClient failing = in -> {
            calls.incrementAndGet();
            throw new RailClient.RailUnavailableException("boom");
        };
        var client = new ResilientRailClient(failing, railConfig(Duration.ofSeconds(2)), fastBreaker(Duration.ofMinutes(5)));
        var in = new RailClient.Instruction("k", "bank:x", 100, "USD");

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.submit(in)).isInstanceOf(RailClient.RailUnavailableException.class);
        }
        assertThat(client.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int before = calls.get();

        assertThatThrownBy(() -> client.submit(in)).hasMessageContaining("circuit breaker is open");
        assertThat(calls.get()).as("rail must not be called while open").isEqualTo(before);
    }

    @Test
    void breakerHalfOpensAfterTheWaitAndClosesAgainWhenTheRailRecovers() throws Exception {
        var healthy = new java.util.concurrent.atomic.AtomicBoolean(false);
        RailClient flaky = in -> {
            if (!healthy.get()) {
                throw new RailClient.RailUnavailableException("down");
            }
            return new RailClient.Result(RailClient.Outcome.SETTLED, "r", "ok");
        };
        var client = new ResilientRailClient(flaky, railConfig(Duration.ofSeconds(2)), fastBreaker(Duration.ofMillis(100)));
        var in = new RailClient.Instruction("k", "bank:x", 100, "USD");
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.submit(in)).isInstanceOf(RailClient.RailUnavailableException.class);
        }
        assertThat(client.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        healthy.set(true);
        Thread.sleep(150);
        client.breaker().transitionToHalfOpenState();
        assertThat(client.submit(in).outcome()).isEqualTo(RailClient.Outcome.SETTLED);
        assertThat(client.breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aHangingRailIsCutOffByTheTimeoutAndCountsAsAFailure() {
        RailClient hanging = in -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new RailClient.Result(RailClient.Outcome.SETTLED, "r", "late");
        };
        var client = new ResilientRailClient(hanging, railConfig(Duration.ofMillis(200)), fastBreaker(Duration.ofMinutes(5)));
        long start = System.nanoTime();

        assertThatThrownBy(() -> client.submit(new RailClient.Instruction("k", "bank:x", 100, "USD")))
                .isInstanceOf(RailClient.RailUnavailableException.class).hasMessageContaining("timed out");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(client.breaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void definitiveRejectionIsNotACircuitBreakerFailure() throws Exception {
        RailClient rejecting = in -> new RailClient.Result(RailClient.Outcome.REJECTED, "r", "no");
        var client = new ResilientRailClient(rejecting, railConfig(Duration.ofSeconds(2)), fastBreaker(Duration.ofMinutes(5)));
        for (int i = 0; i < 10; i++) {
            assertThat(client.submit(new RailClient.Instruction("k" + i, "bank:x", 100, "USD")).outcome())
                    .isEqualTo(RailClient.Outcome.REJECTED);
        }
        assertThat(client.breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
