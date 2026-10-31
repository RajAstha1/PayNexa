package io.paynexa.notifications.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationStore {

    public record Notification(UUID id, UUID paymentId, String kind, String message, Instant createdAt, boolean read) {}

    public record DeadLetter(long id, String topic, String key, String payload, String error, String status, Instant createdAt) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public NotificationStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** @return true if this is the first time the event id is seen (the primary key decides, race-free). */
    public boolean claimEvent(UUID eventId, String type, UUID paymentId, int sequence) {
        return jdbc.sql("""
                INSERT INTO inbox (event_id, event_type, payment_id, sequence, outcome, received_at)
                VALUES (:id, :type, :payment, :seq, 'APPLIED', :now) ON CONFLICT (event_id) DO NOTHING""")
                .param("id", eventId).param("type", type).param("payment", paymentId).param("seq", sequence).param("now", now())
                .update() == 1;
    }

    /** @return true if the event is newer than everything applied so far for the payment. */
    public boolean advanceProgress(UUID paymentId, int sequence) {
        return jdbc.sql("""
                INSERT INTO payment_progress (payment_id, last_sequence, updated_at) VALUES (:p, :s, :now)
                ON CONFLICT (payment_id) DO UPDATE SET last_sequence = EXCLUDED.last_sequence, updated_at = EXCLUDED.updated_at
                 WHERE payment_progress.last_sequence < EXCLUDED.last_sequence""")
                .param("p", paymentId).param("s", sequence).param("now", now()).update() == 1;
    }

    public void markStale(UUID eventId) {
        jdbc.sql("UPDATE inbox SET outcome = 'STALE' WHERE event_id = :id").param("id", eventId).update();
    }

    public void addNotification(UUID userId, UUID paymentId, UUID eventId, String kind, String message) {
        jdbc.sql("""
                INSERT INTO notifications (id, user_id, payment_id, event_id, kind, message, created_at)
                VALUES (:id, :u, :p, :e, :k, :m, :now) ON CONFLICT (event_id, user_id) DO NOTHING""")
                .param("id", UUID.randomUUID()).param("u", userId).param("p", paymentId).param("e", eventId)
                .param("k", kind).param("m", message).param("now", now()).update();
    }

    public List<Notification> forUser(UUID userId, int limit) {
        return jdbc.sql("SELECT id, payment_id, kind, message, created_at, read_at FROM notifications WHERE user_id = :u ORDER BY created_at DESC, id LIMIT :l")
                .param("u", userId).param("l", limit)
                .query((rs, n) -> new Notification(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                        rs.getString("kind"), rs.getString("message"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("read_at") != null)).list();
    }

    public boolean markRead(UUID userId, UUID id) {
        return jdbc.sql("UPDATE notifications SET read_at = COALESCE(read_at, :now) WHERE id = :id AND user_id = :u")
                .param("now", now()).param("id", id).param("u", userId).update() == 1;
    }

    public long countInbox() {
        return jdbc.sql("SELECT count(*) FROM inbox").query(Long.class).single();
    }

    // --------------------------------------------------------------------------------------------- dead letters

    public void addDeadLetter(String topic, String key, String payload, String error) {
        jdbc.sql("INSERT INTO dead_letters (topic, record_key, payload, error, created_at) VALUES (:t, :k, :p, :e, :now)")
                .param("t", topic).param("k", key).param("p", payload)
                .param("e", error == null ? null : error.substring(0, Math.min(500, error.length()))).param("now", now()).update();
    }

    public List<DeadLetter> deadLetters(String status, int limit) {
        return jdbc.sql("SELECT id, topic, record_key, payload, error, status, created_at FROM dead_letters WHERE status = :s ORDER BY id LIMIT :l")
                .param("s", status).param("l", limit)
                .query((rs, n) -> new DeadLetter(rs.getLong("id"), rs.getString("topic"), rs.getString("record_key"),
                        rs.getString("payload"), rs.getString("error"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant())).list();
    }

    public java.util.Optional<DeadLetter> deadLetterForUpdate(long id) {
        return jdbc.sql("SELECT id, topic, record_key, payload, error, status, created_at FROM dead_letters WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query((rs, n) -> new DeadLetter(rs.getLong("id"), rs.getString("topic"), rs.getString("record_key"),
                        rs.getString("payload"), rs.getString("error"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant())).optional();
    }

    public void markReplayed(long id) {
        jdbc.sql("UPDATE dead_letters SET status = 'REPLAYED', replayed_at = :now WHERE id = :id").param("now", now()).param("id", id).update();
    }

    public long countDeadLetters(String status) {
        return jdbc.sql("SELECT count(*) FROM dead_letters WHERE status = :s").param("s", status).query(Long.class).single();
    }
}
