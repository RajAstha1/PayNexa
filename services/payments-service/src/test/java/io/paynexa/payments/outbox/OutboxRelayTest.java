package io.paynexa.payments.outbox;

import io.paynexa.contracts.PaymentEvent;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.service.PaymentService.TransferCommand;
import io.paynexa.payments.support.RecordingPublisher;
import io.paynexa.payments.support.TestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayTest {

    static TestKit kit;
    RecordingPublisher broker;
    OutboxRelay relay;

    @BeforeAll
    static void start() {
        kit = TestKit.create();
    }

    @AfterAll
    static void stop() {
        kit.close();
    }

    @BeforeEach
    void freshBroker() {
        // Drain anything left by other tests so each test sees only its own events.
        kit.jdbc.sql("UPDATE outbox_events SET status = 'PUBLISHED' WHERE status <> 'PUBLISHED'").update();
        broker = new RecordingPublisher();
        relay = new OutboxRelay(kit.tx, kit.outbox, broker, kit.props, kit.clock, kit.meters);
    }

    private UUID makePayment() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "100.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        // setup noise only: hide the funding events, never other payments' events
        kit.jdbc.sql("UPDATE outbox_events SET status = 'PUBLISHED' WHERE status = 'PENDING' AND payload->>'kind' = 'FUNDING'").update();
        return kit.paymentService.transfer(alice, "key-" + UUID.randomUUID(),
                new TransferCommand(a.id(), b.id(), Money.parse("1.00", "USD"), null)).payment().id();
    }

    @Test
    void publishesCommittedEventsOnceAndMarksThemPublished() {
        var id = makePayment();

        assertThat(relay.runOnce()).isEqualTo(1);

        assertThat(broker.sent).hasSize(1);
        var sent = broker.sent.get(0);
        assertThat(sent.topic()).isEqualTo(PaymentEvent.TOPIC);
        assertThat(sent.key()).isEqualTo(id.toString());
        assertThat(sent.headers()).containsKeys("event-id", "event-type", "sequence");
        assertThat(sent.payload()).contains("payment.completed").doesNotContain("password");
        assertThat(relay.runOnce()).isZero();             // nothing is published twice in normal operation
        assertThat(broker.sent).hasSize(1);
    }

    @Test
    void anEventRolledBackWithItsTransactionIsNeverPublished() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "10.00");
        kit.jdbc.sql("UPDATE outbox_events SET status = 'PUBLISHED' WHERE status = 'PENDING' AND payload->>'kind' = 'FUNDING'").update();
        try {
            kit.paymentService.transfer(alice, "key-" + UUID.randomUUID(),
                    new TransferCommand(a.id(), UUID.randomUUID(), Money.parse("1.00", "USD"), null)); // unknown payee -> rolled back
        } catch (RuntimeException expected) {
            // request error
        }
        assertThat(relay.runOnce()).isZero();
        assertThat(broker.sent).isEmpty();
    }

    @Test
    void brokerOutageDelaysPublicationWithBackoffThenRecoversWithoutLoss() {
        var id = makePayment();
        broker.failWhen = s -> true;

        assertThat(relay.runOnce()).isZero();
        var row = kit.jdbc.sql("SELECT status, attempts, last_error FROM outbox_events WHERE aggregate_id = :a")
                .param("a", id).query((rs, n) -> rs.getString(1) + "|" + rs.getInt(2) + "|" + rs.getString(3)).single();
        assertThat(row).startsWith("PENDING|1|").contains("broker unavailable");

        // backoff: not retried until due
        int attemptsAfterFirst = broker.attempts.get();
        assertThat(relay.runOnce()).isZero();
        assertThat(broker.attempts.get()).isEqualTo(attemptsAfterFirst);

        broker.failWhen = s -> false;                     // broker comes back
        kit.clock.advance(Duration.ofSeconds(2));
        assertThat(relay.runOnce()).isEqualTo(1);
        assertThat(broker.sent).hasSize(1);
        assertThat(kit.count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND status = 'PUBLISHED'", id)).isEqualTo(1);
    }

    @Test
    void paymentsStillSucceedWhileTheBrokerIsDown() {
        broker.failWhen = s -> true;
        var id = makePayment();                           // business transaction does not depend on Kafka
        relay.runOnce();
        assertThat(kit.payments.find(id)).isPresent();
        assertThat(kit.count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND status = 'PENDING'", id)).isEqualTo(1);
    }

    @Test
    void exhaustedEventsBecomeDeadStopBlockingAndCanBeRequeuedByAnAdmin() {
        var poison = makePayment();
        var healthy = makePayment();
        broker.failWhen = s -> s.key().equals(poison.toString());

        for (int i = 0; i < 3; i++) {                     // maxAttempts = 3 in the test configuration
            relay.runOnce();
            kit.clock.advance(Duration.ofSeconds(5));
        }
        relay.runOnce();

        assertThat(kit.count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND status = 'DEAD'", poison)).isEqualTo(1);
        assertThat(kit.count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND status = 'PUBLISHED'", healthy)).isEqualTo(1);
        assertThat(kit.outbox.count("DEAD")).isGreaterThanOrEqualTo(1);

        broker.failWhen = s -> false;                     // root cause fixed
        assertThat(kit.outbox.requeueDead()).isGreaterThanOrEqualTo(1);
        relay.runOnce();
        assertThat(kit.count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND status = 'PUBLISHED'", poison)).isEqualTo(1);
        assertThat(broker.sent).extracting(RecordingPublisher.Sent::key).contains(poison.toString());
    }

    @Test
    void aFailingEventBlocksLaterEventsInThePassToPreserveOrder() {
        var first = makePayment();
        var second = makePayment();
        broker.failWhen = s -> s.key().equals(first.toString());

        relay.runOnce();

        assertThat(broker.sent).extracting(RecordingPublisher.Sent::key).doesNotContain(second.toString());
        broker.failWhen = s -> false;
        kit.clock.advance(Duration.ofSeconds(5));
        relay.runOnce();
        assertThat(broker.sent).extracting(RecordingPublisher.Sent::key).containsExactly(first.toString(), second.toString());
    }

    @Test
    void lostAcknowledgementCausesADuplicatePublishWhichIsTheDocumentedAtLeastOnceBehaviour() {
        var id = makePayment();
        broker.throwAfterRecording = true;                // broker has the event, relay believes it failed
        relay.runOnce();
        broker.throwAfterRecording = false;
        kit.clock.advance(Duration.ofSeconds(5));
        relay.runOnce();

        var copies = broker.sent.stream().filter(s -> s.key().equals(id.toString())).toList();
        assertThat(copies).hasSize(2);
        // consumers deduplicate on this id; both copies carry the same one
        assertThat(copies.get(0).headers().get("event-id")).isEqualTo(copies.get(1).headers().get("event-id"));
    }

    @Test
    void onlyOneRelayInstanceCanPublishAtATime() throws Exception {
        for (int i = 0; i < 5; i++) {
            makePayment();
        }
        var inFlight = new AtomicInteger();
        var maxInFlight = new AtomicInteger();
        var slow = new RecordingPublisher() {
            @Override
            public void publish(String topic, String key, String payload, java.util.Map<String, String> headers) throws Exception {
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                Thread.sleep(40);
                super.publish(topic, key, payload, headers);
                inFlight.decrementAndGet();
            }
        };
        var relays = List.of(new OutboxRelay(kit.tx, kit.outbox, slow, kit.props, kit.clock, kit.meters),
                new OutboxRelay(kit.tx, kit.outbox, slow, kit.props, kit.clock, kit.meters),
                new OutboxRelay(kit.tx, kit.outbox, slow, kit.props, kit.clock, kit.meters));
        var pool = Executors.newFixedThreadPool(3);
        var go = new CountDownLatch(1);
        for (var r : relays) {
            pool.submit(() -> { go.await(); return r.runOnce(); });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(maxInFlight.get()).as("concurrent publishers").isEqualTo(1);
        assertThat(slow.sent).hasSize(5);
        assertThat(slow.sent.stream().map(RecordingPublisher.Sent::key).distinct().count()).isEqualTo(5);
    }
}
