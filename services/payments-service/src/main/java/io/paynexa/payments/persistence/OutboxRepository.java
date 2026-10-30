package io.paynexa.payments.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    public record Row(long id, UUID eventId, UUID aggregateId, String eventType, int sequence, String payload,
                      int attempts, String correlationId) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public OutboxRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Called inside the business transaction: the event exists if and only if the state change commits. */
    public void insert(UUID eventId, UUID aggregateId, String type, int sequence, String payloadJson) {
        jdbc.sql("""
                INSERT INTO outbox_events (event_id, aggregate_id, event_type, sequence, payload, correlation_id, next_attempt_at, created_at)
                VALUES (:eid, :agg, :type, :seq, CAST(:payload AS jsonb), :corr, :now, :now)""")
                .param("eid", eventId).param("agg", aggregateId).param("type", type).param("seq", sequence)
                .param("payload", payloadJson).param("corr", io.paynexa.platform.Correlation.current())
                .param("now", Times.toDb(clock.instant())).update();
    }

    /** Due events in outbox order. Caller must hold the relay advisory lock. */
    public List<Row> due(int limit) {
        return jdbc.sql("""
                SELECT id, event_id, aggregate_id, event_type, sequence, payload::text AS payload, attempts, correlation_id
                  FROM outbox_events WHERE status = 'PENDING' AND next_attempt_at <= :now
                 ORDER BY id LIMIT :limit""")
                .param("now", Times.toDb(clock.instant())).param("limit", limit)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), rs.getInt("sequence"),
                        rs.getString("payload"), rs.getInt("attempts"), rs.getString("correlation_id")))
                .list();
    }

    public void markPublished(long id) {
        jdbc.sql("UPDATE outbox_events SET status = 'PUBLISHED', published_at = :now, last_error = NULL WHERE id = :id")
                .param("now", Times.toDb(clock.instant())).param("id", id).update();
    }

    public void markFailed(long id, int attempts, Instant nextAttempt, String error, boolean dead) {
        jdbc.sql("""
                UPDATE outbox_events SET attempts = :attempts, next_attempt_at = :next, last_error = :error,
                       status = CASE WHEN :dead THEN 'DEAD' ELSE 'PENDING' END
                 WHERE id = :id""")
                .param("attempts", attempts).param("next", Times.toDb(nextAttempt))
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 500)))
                .param("dead", dead).param("id", id).update();
    }

    /** Admin recovery: gives dead events a fresh budget. Returns how many were requeued. */
    public int requeueDead() {
        return jdbc.sql("UPDATE outbox_events SET status = 'PENDING', attempts = 0, next_attempt_at = :now WHERE status = 'DEAD'")
                .param("now", Times.toDb(clock.instant())).update();
    }

    public int requeueDead(long id) {
        return jdbc.sql("UPDATE outbox_events SET status = 'PENDING', attempts = 0, next_attempt_at = :now WHERE status = 'DEAD' AND id = :id")
                .param("now", Times.toDb(clock.instant())).param("id", id).update();
    }

    public long count(String status) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = :s").param("s", status).query(Long.class).single();
    }

    public long countPendingOlderThan(Instant t) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = 'PENDING' AND created_at < :t")
                .param("t", Times.toDb(t)).query(Long.class).single();
    }

    /** Single active publisher: a transaction-scoped advisory lock. Returns false if another instance holds it. */
    public boolean tryRelayLock() {
        return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(727001)").query(Boolean.class).single());
    }
}
