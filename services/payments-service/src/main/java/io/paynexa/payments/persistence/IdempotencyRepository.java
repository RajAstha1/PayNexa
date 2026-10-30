package io.paynexa.payments.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency keys, scoped to (user, key).
 *
 * <p>{@link #claim} is one atomic statement. If another transaction holds an uncommitted claim for the same key,
 * PostgreSQL makes this statement wait until that transaction ends, so two concurrent identical requests can never
 * both proceed. An expired key is atomically taken over by the new request.
 */
@Repository
public class IdempotencyRepository {

    public record Stored(UUID paymentId, String fingerprint) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration retention;

    public IdempotencyRepository(JdbcClient jdbc, Clock clock, io.paynexa.payments.config.PaymentsProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.retention = props.idempotency().retention();
    }

    /** @return true if this transaction now owns the key; false if a live claim already exists. */
    public boolean claim(UUID userId, String key, String fingerprint, UUID paymentId) {
        var now = clock.instant();
        return jdbc.sql("""
                INSERT INTO idempotency_keys (user_id, idem_key, fingerprint, payment_id, created_at, expires_at)
                VALUES (:user, :key, :fp, :payment, :now, :expires)
                ON CONFLICT (user_id, idem_key) DO UPDATE
                   SET fingerprint = EXCLUDED.fingerprint, payment_id = EXCLUDED.payment_id,
                       created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at
                 WHERE idempotency_keys.expires_at <= :now""")
                .param("user", userId).param("key", key).param("fp", fingerprint).param("payment", paymentId)
                .param("now", Times.toDb(now)).param("expires", Times.toDb(now.plus(retention)))
                .update() == 1;
    }

    public Optional<Stored> find(UUID userId, String key) {
        return jdbc.sql("SELECT payment_id, fingerprint FROM idempotency_keys WHERE user_id = :u AND idem_key = :k")
                .param("u", userId).param("k", key)
                .query((rs, n) -> new Stored(rs.getObject("payment_id", UUID.class), rs.getString("fingerprint")))
                .optional();
    }

    public int deleteExpired() {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at <= :now").param("now", Times.toDb(clock.instant())).update();
    }
}
