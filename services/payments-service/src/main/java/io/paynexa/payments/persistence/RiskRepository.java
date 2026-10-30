package io.paynexa.payments.persistence;

import io.paynexa.payments.risk.RiskDecision;
import io.paynexa.payments.risk.RiskStats;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class RiskRepository implements RiskStats {

    private final JdbcClient jdbc;
    private final Clock clock;

    public RiskRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public long outflowSince(UUID payerAccountId, Instant since) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM payments
                 WHERE payer_account_id = :a AND created_at >= :since AND kind <> 'FUNDING'
                   AND status IN ('COMPLETED', 'SUBMITTED', 'SETTLED')""")
                .param("a", payerAccountId).param("since", Times.toDb(since)).query(Long.class).single();
    }

    @Override
    public long paymentCountSince(UUID payerAccountId, Instant since) {
        return jdbc.sql("""
                SELECT count(*) FROM payments
                 WHERE payer_account_id = :a AND created_at >= :since AND kind <> 'FUNDING'
                   AND status IN ('COMPLETED', 'SUBMITTED', 'SETTLED', 'PENDING_REVIEW')""")
                .param("a", payerAccountId).param("since", Times.toDb(since)).query(Long.class).single();
    }

    @Override
    public boolean hasPaidBefore(UUID payerAccountId, UUID payeeAccountId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM payments WHERE payer_account_id = :a AND payee_account_id = :b
                                  AND status IN ('COMPLETED', 'REVERSED'))""")
                .param("a", payerAccountId).param("b", payeeAccountId).query(Boolean.class).single();
    }

    public void record(UUID paymentId, RiskDecision.Outcome outcome) {
        // hits are built from fixed rule names and generated detail text, never from user input
        var json = outcome.hits().stream()
                .map(h -> "{\"rule\":\"%s\",\"decision\":\"%s\",\"detail\":\"%s\"}"
                        .formatted(h.rule(), h.decision(), h.detail().replace("\\", "\\\\").replace("\"", "\\\"")))
                .collect(Collectors.joining(",", "[", "]"));
        jdbc.sql("INSERT INTO risk_decisions (payment_id, decision, hits, created_at) VALUES (:p, :d, CAST(:hits AS jsonb), :now)")
                .param("p", paymentId).param("d", outcome.decision().name()).param("hits", json)
                .param("now", Times.toDb(clock.instant())).update();
    }
}
