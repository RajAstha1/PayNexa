package io.paynexa.notifications;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.paynexa.contracts.PaymentEvent;
import io.paynexa.notifications.consumer.PaymentEventProcessor;
import io.paynexa.notifications.consumer.PaymentEventProcessor.Outcome;
import io.paynexa.notifications.consumer.PaymentEventProcessor.UnprocessableEventException;
import io.paynexa.notifications.persistence.NotificationStore;
import io.paynexa.testsupport.PostgresTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentEventProcessorTest {

    static HikariDataSource ds;
    static JdbcClient jdbc;
    static NotificationStore store;
    static PaymentEventProcessor processor;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void start() {
        var cfg = new HikariConfig();
        cfg.setDataSource(PostgresTestDatabase.migrated("notifications", "classpath:db/migration"));
        cfg.setMaximumPoolSize(20);
        ds = new HikariDataSource(cfg);
        jdbc = JdbcClient.create(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        store = new NotificationStore(jdbc, Clock.systemUTC());
        processor = new PaymentEventProcessor(store, tx, new SimpleMeterRegistry());
    }

    @AfterAll
    static void stop() {
        ds.close();
    }

    private static PaymentEvent event(UUID payment, int seq, String type, String kind, UUID payer, UUID payee, long minor) {
        return new PaymentEvent(UUID.randomUUID(), type, Instant.now(), payment, seq, kind,
                type.substring(type.indexOf('.') + 1).toUpperCase(), payer, payee, minor, "USD", null);
    }

    private static String json(PaymentEvent e) {
        return JSON.writeValueAsString(e);
    }

    private long notificationsFor(UUID user) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = :u").param("u", user).query(Long.class).single();
    }

    @Test
    void completedTransferNotifiesBothPartiesWithFormattedAmount() {
        var payer = UUID.randomUUID();
        var payee = UUID.randomUUID();
        var e = event(UUID.randomUUID(), 1, PaymentEvent.COMPLETED, "TRANSFER", payer, payee, 12_345);

        assertThat(processor.process(json(e))).isEqualTo(Outcome.APPLIED);

        var sent = store.forUser(payer, 10);
        var received = store.forUser(payee, 10);
        assertThat(sent).singleElement().satisfies(n -> assertThat(n.message()).contains("123.45 USD").contains("completed"));
        assertThat(received).singleElement().satisfies(n -> assertThat(n.kind()).isEqualTo("PAYMENT_RECEIVED"));
    }

    @Test
    void aRedeliveredEventHasNoSecondEffect() {
        var payer = UUID.randomUUID();
        var e = event(UUID.randomUUID(), 1, PaymentEvent.COMPLETED, "TRANSFER", payer, UUID.randomUUID(), 100);

        assertThat(processor.process(json(e))).isEqualTo(Outcome.APPLIED);
        assertThat(processor.process(json(e))).isEqualTo(Outcome.DUPLICATE);
        assertThat(processor.process(json(e))).isEqualTo(Outcome.DUPLICATE);

        assertThat(notificationsFor(payer)).isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesOfTheSameEventApplyExactlyOnce() throws Exception {
        var payer = UUID.randomUUID();
        var payload = json(event(UUID.randomUUID(), 1, PaymentEvent.COMPLETED, "TRANSFER", payer, UUID.randomUUID(), 100));
        var pool = Executors.newFixedThreadPool(10);
        var go = new CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<Outcome>>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> { go.await(); return processor.process(payload); }));
        }
        go.countDown();
        int applied = 0;
        for (var f : futures) {
            if (f.get(30, TimeUnit.SECONDS) == Outcome.APPLIED) applied++;
        }
        pool.shutdown();
        assertThat(applied).isEqualTo(1);
        assertThat(notificationsFor(payer)).isEqualTo(1);
    }

    @Test
    void anOlderEventArrivingAfterANewerOneIsDiscardedAsStale() {
        var payment = UUID.randomUUID();
        var payer = UUID.randomUUID();
        var submitted = event(payment, 1, PaymentEvent.SUBMITTED, "PAYOUT", payer, null, 500);
        var settled = event(payment, 2, PaymentEvent.SETTLED, "PAYOUT", payer, null, 500);

        assertThat(processor.process(json(settled))).isEqualTo(Outcome.APPLIED);     // newer first
        assertThat(processor.process(json(submitted))).isEqualTo(Outcome.STALE);     // older arrives late

        assertThat(store.forUser(payer, 10)).extracting(NotificationStore.Notification::kind).containsExactly("PAYOUT_SETTLED");
        assertThat(jdbc.sql("SELECT outcome FROM inbox WHERE event_id = :e").param("e", submitted.eventId()).query(String.class).single())
                .isEqualTo("STALE");
    }

    @Test
    void eventsForDifferentPaymentsDoNotAffectEachOthersOrdering() {
        var payer = UUID.randomUUID();
        var a = event(UUID.randomUUID(), 5, PaymentEvent.COMPLETED, "TRANSFER", payer, null, 100);
        var b = event(UUID.randomUUID(), 1, PaymentEvent.COMPLETED, "TRANSFER", payer, null, 100);
        assertThat(processor.process(json(a))).isEqualTo(Outcome.APPLIED);
        assertThat(processor.process(json(b))).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void failureMidwayRollsBackTheInboxClaimSoARetrySucceedsWithoutDuplicates() {
        var payer = UUID.randomUUID();
        var e = event(UUID.randomUUID(), 1, PaymentEvent.COMPLETED, "TRANSFER", payer, null, 100);
        // Break the notifications table so the write after the inbox claim fails.
        jdbc.sql("ALTER TABLE notifications RENAME TO notifications_off").update();
        try {
            assertThatThrownBy(() -> processor.process(json(e))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.sql("ALTER TABLE notifications_off RENAME TO notifications").update();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM inbox WHERE event_id = :e").param("e", e.eventId()).query(Long.class).single())
                .as("inbox claim must roll back with the failed effect").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM payment_progress WHERE payment_id = :p").param("p", e.paymentId()).query(Long.class).single()).isZero();

        assertThat(processor.process(json(e))).isEqualTo(Outcome.APPLIED);          // the broker redelivers; now it works
        assertThat(notificationsFor(payer)).isEqualTo(1);
    }

    @Test
    void reversalAndFailureNotificationsAreWrittenSafely() {
        var payer = UUID.randomUUID();
        var payee = UUID.randomUUID();
        var payment = UUID.randomUUID();
        processor.process(json(event(payment, 1, PaymentEvent.COMPLETED, "TRANSFER", payer, payee, 100)));
        processor.process(json(event(payment, 2, PaymentEvent.REVERSED, "TRANSFER", payer, payee, 100)));
        assertThat(notificationsFor(payer)).isEqualTo(2);
        assertThat(notificationsFor(payee)).isEqualTo(2);

        var failed = new PaymentEvent(UUID.randomUUID(), PaymentEvent.FAILED, Instant.now(), UUID.randomUUID(), 1, "TRANSFER", "FAILED",
                payer, payee, 100, "USD", "INSUFFICIENT_FUNDS\n<script>alert(1)</script>");
        processor.process(json(failed));
        assertThat(store.forUser(payer, 10).stream().map(NotificationStore.Notification::message))
                .noneMatch(m -> m.contains("<script>") || m.contains("\n"));
    }

    @Test
    void unknownEventTypesAndUnknownFieldsAreToleratedForForwardCompatibility() {
        var payer = UUID.randomUUID();
        var payload = ("{\"eventId\":\"%s\",\"type\":\"payment.future_thing\",\"paymentId\":\"%s\",\"sequence\":1,\"payerUserId\":\"%s\","
                + "\"amountMinor\":5,\"currency\":\"USD\",\"brandNewField\":{\"x\":1}}").formatted(UUID.randomUUID(), UUID.randomUUID(), payer);
        assertThat(processor.process(payload)).isEqualTo(Outcome.APPLIED);
        assertThat(notificationsFor(payer)).isZero();
    }

    @Test
    void malformedOrIncompleteEventsAreClassifiedAsUnprocessableNotRetried() {
        for (var bad : new String[] {"not json", "{}", "{\"eventId\":\"" + UUID.randomUUID() + "\"}",
                "{\"eventId\":\"x\",\"paymentId\":\"y\",\"type\":\"payment.completed\",\"sequence\":1}"}) {
            assertThatThrownBy(() -> processor.process(bad)).as(bad).isInstanceOf(UnprocessableEventException.class);
        }
    }

    @Test
    void usersOnlyEverSeeTheirOwnNotifications() {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        processor.process(json(event(UUID.randomUUID(), 1, PaymentEvent.COMPLETED, "TRANSFER", alice, null, 100)));
        assertThat(store.forUser(bob, 10)).isEmpty();
        var id = store.forUser(alice, 10).get(0).id();
        assertThat(store.markRead(bob, id)).as("another user cannot mark it").isFalse();
        assertThat(store.markRead(alice, id)).isTrue();
        assertThat(store.forUser(alice, 10).get(0).read()).isTrue();
    }
}
