package io.paynexa.payments.persistence;

import io.paynexa.payments.domain.PaymentStatus;
import io.paynexa.platform.Correlation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Append-only status history and administrative audit log. */
@Repository
public class HistoryRepository {

    public record HistoryRow(PaymentStatus from, PaymentStatus to, String actor, String reason, java.time.Instant at) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public HistoryRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void recordTransition(UUID paymentId, PaymentStatus from, PaymentStatus to, String actor, String reason) {
        jdbc.sql("""
                INSERT INTO payment_status_history (payment_id, from_status, to_status, actor, reason, correlation_id, at)
                VALUES (:p, :from, :to, :actor, :reason, :corr, :now)""")
                .param("p", paymentId).param("from", from == null ? null : from.name()).param("to", to.name())
                .param("actor", actor).param("reason", reason).param("corr", Correlation.current())
                .param("now", Times.toDb(clock.instant())).update();
    }

    public List<HistoryRow> history(UUID paymentId) {
        return jdbc.sql("SELECT from_status, to_status, actor, reason, at FROM payment_status_history WHERE payment_id = :p ORDER BY id")
                .param("p", paymentId)
                .query((rs, n) -> new HistoryRow(
                        rs.getString("from_status") == null ? null : PaymentStatus.valueOf(rs.getString("from_status")),
                        PaymentStatus.valueOf(rs.getString("to_status")), rs.getString("actor"), rs.getString("reason"),
                        Times.read(rs, "at")))
                .list();
    }

    /** {@code detailsJson} must be a JSON object that contains no secrets or personal data. */
    public void audit(String actor, String action, String targetType, String targetId, String detailsJson) {
        jdbc.sql("""
                INSERT INTO audit_log (at, actor, action, target_type, target_id, details, correlation_id)
                VALUES (:now, :actor, :action, :tt, :tid, CAST(:details AS jsonb), :corr)""")
                .param("now", Times.toDb(clock.instant())).param("actor", actor).param("action", action)
                .param("tt", targetType).param("tid", targetId).param("details", detailsJson)
                .param("corr", Correlation.current()).update();
    }
}
