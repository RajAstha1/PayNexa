package io.paynexa.notifications;

import io.paynexa.contracts.PaymentEvent;
import io.paynexa.testsupport.PostgresTestDatabase;
import io.paynexa.testsupport.TestTokens;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Kafka broker (in-JVM, KRaft) + real PostgreSQL + the real consumer, error handler and dead-letter flow.
 */
@SpringBootTest(properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "paynexa.notifications.retry.initial-interval=100ms",
        "paynexa.notifications.retry.max-interval=300ms",
        "paynexa.notifications.retry.max-attempts=3",
        "spring.flyway.user=postgres", "spring.flyway.password=postgres",
        "spring.datasource.username=postgres", "spring.datasource.password=postgres"})
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {PaymentEvent.TOPIC, PaymentEvent.TOPIC + PaymentEvent.DEAD_LETTER_SUFFIX})
class KafkaFlowTest {

    static final TestTokens TOKENS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PostgresTestDatabase.migratedJdbcUrl("notifications", "classpath:db/migration"));
        r.add("paynexa.security.jwt.public-key", TOKENS::publicKeyPem);
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    private static PaymentEvent event(UUID payment, int seq, String type, UUID payer) {
        return new PaymentEvent(UUID.randomUUID(), type, Instant.now(), payment, seq, "TRANSFER", "COMPLETED", payer, UUID.randomUUID(), 2_500, "USD", null);
    }

    private void send(String key, String payload) throws Exception {
        kafka.send(PaymentEvent.TOPIC, key, payload).get();
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    @Test
    void publishedEventIsConsumedAndDuplicatePublishesAreIgnored() throws Exception {
        var payer = UUID.randomUUID();
        var e = event(UUID.randomUUID(), 1, PaymentEvent.COMPLETED, payer);
        var payload = JSON.writeValueAsString(e);

        send(e.paymentId().toString(), payload);
        send(e.paymentId().toString(), payload);      // at-least-once: the broker may deliver the same event again
        send(e.paymentId().toString(), payload);

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", payer)).isEqualTo(1));
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM inbox WHERE event_id = ?", e.eventId())).isEqualTo(1));
        Thread.sleep(500);                                        // give the duplicates time to be processed
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", payer)).isEqualTo(1);
    }

    @Test
    void outOfOrderEventsOnTheWireAreDiscardedAsStale() throws Exception {
        var payment = UUID.randomUUID();
        var payer = UUID.randomUUID();
        var newer = event(payment, 2, PaymentEvent.REVERSED, payer);
        var older = event(payment, 1, PaymentEvent.COMPLETED, payer);

        send(payment.toString(), JSON.writeValueAsString(newer));
        send(payment.toString(), JSON.writeValueAsString(older));

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM inbox WHERE payment_id = ?", payment)).isEqualTo(2));
        assertThat(count("SELECT count(*) FROM inbox WHERE payment_id = ? AND outcome = 'STALE'", payment)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE payment_id = ? AND kind = 'PAYMENT_SENT'", payment))
                .as("the stale COMPLETED must not produce a 'sent' notification after the reversal").isZero();
    }

    @Test
    void aPoisonMessageIsDeadLetteredWithoutBlockingTheEventsBehindItThenReplayedByAnAdmin() throws Exception {
        var marker = "poison-" + UUID.randomUUID();
        send(marker, "{\"this\":\"is not a payment event\",\"marker\":\"" + marker + "\"}");
        var healthy = event(UUID.randomUUID(), 1, PaymentEvent.COMPLETED, UUID.randomUUID());
        send(healthy.paymentId().toString(), JSON.writeValueAsString(healthy));      // queued behind the poison message

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM dead_letters WHERE payload LIKE ?", "%" + marker + "%")).isEqualTo(1));
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM notifications WHERE payment_id = ?", healthy.paymentId()))
                        .as("one notification each for payer and payee").isEqualTo(2));

        var adminToken = "Bearer " + TOKENS.admin(UUID.randomUUID());
        mvc.perform(get("/admin/dead-letters")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/dead-letters").header("Authorization", "Bearer " + TOKENS.user(UUID.randomUUID()))).andExpect(status().isForbidden());
        var listed = mvc.perform(get("/admin/dead-letters").header("Authorization", adminToken)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(listed).contains(marker);

        var id = jdbc.sql("SELECT id FROM dead_letters WHERE payload LIKE ?").param("%" + marker + "%").query(Long.class).single();
        // The payload still cannot be processed, so the replay lands in the dead-letter store again: recovery is explicit and safe.
        mvc.perform(post("/admin/dead-letters/" + id + "/replay").header("Authorization", adminToken)).andExpect(status().isOk());
        mvc.perform(post("/admin/dead-letters/" + id + "/replay").header("Authorization", adminToken)).andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM dead_letters WHERE id = ? AND status = 'REPLAYED'", id)).isEqualTo(1);
        // The payload is still unprocessable, so the replay fails again and is recorded as a NEW dead letter: the
        // failure is never lost. The original row stays REPLAYED, giving operators a complete history.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM dead_letters WHERE payload LIKE ? AND status = 'NEW'", "%" + marker + "%")).isEqualTo(1));
        assertThat(count("SELECT count(*) FROM dead_letters WHERE payload LIKE ? AND status = 'REPLAYED'", "%" + marker + "%")).isEqualTo(1);
    }

    @Test
    void aDeadLetteredValidEventCanBeRecoveredByReplay() throws Exception {
        // A valid event that landed in the dead-letter store (e.g. the database was down for longer than the retries).
        var payer = UUID.randomUUID();
        var e = event(UUID.randomUUID(), 1, PaymentEvent.COMPLETED, payer);
        jdbc.sql("INSERT INTO dead_letters (topic, record_key, payload, error, created_at) VALUES (?, ?, ?, 'db down', now())")
                .params(PaymentEvent.TOPIC, e.paymentId().toString(), JSON.writeValueAsString(e)).update();
        var id = jdbc.sql("SELECT max(id) FROM dead_letters").query(Long.class).single();

        mvc.perform(post("/admin/dead-letters/" + id + "/replay").header("Authorization", "Bearer " + TOKENS.admin(UUID.randomUUID())))
                .andExpect(status().isOk());

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", payer)).isEqualTo(1));
    }

    @Test
    void usersReadOnlyTheirOwnNotificationsOverHttp() throws Exception {
        var alice = UUID.randomUUID();
        var e = event(UUID.randomUUID(), 1, PaymentEvent.COMPLETED, alice);
        send(e.paymentId().toString(), JSON.writeValueAsString(e));
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", alice)).isEqualTo(1));

        var mine = mvc.perform(get("/notifications").header("Authorization", "Bearer " + TOKENS.user(alice))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        var others = mvc.perform(get("/notifications").header("Authorization", "Bearer " + TOKENS.user(UUID.randomUUID()))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(mine).contains("PAYMENT_SENT");
        assertThat(others).isEqualTo("[]");
        mvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
    }
}
