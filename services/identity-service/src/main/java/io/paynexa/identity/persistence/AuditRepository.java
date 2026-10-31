package io.paynexa.identity.persistence;

import io.paynexa.platform.Correlation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Security audit trail. Records who and what happened, never credentials, tokens or email addresses. */
@Repository
public class AuditRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(String action, UUID userId, String outcome, String detail) {
        jdbc.sql("INSERT INTO audit_log (at, action, user_id, outcome, detail, correlation_id) VALUES (:at, :a, :u, :o, :d, :c)")
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).param("a", action).param("u", userId)
                .param("o", outcome).param("d", detail).param("c", Correlation.current()).update();
    }
}
