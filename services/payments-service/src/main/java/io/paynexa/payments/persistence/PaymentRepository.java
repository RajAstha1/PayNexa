package io.paynexa.payments.persistence;

import io.paynexa.payments.domain.DomainException;
import io.paynexa.payments.domain.Payment;
import io.paynexa.payments.domain.PaymentKind;
import io.paynexa.payments.domain.PaymentStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentRepository {

    private static final String COLUMNS = """
            id, kind, status, initiator_user_id, payer_account_id, payee_account_id, destination_ref, amount_minor,
            currency, reference, failure_reason, sequence, rail_reference, rail_attempts, rail_next_attempt_at,
            created_at, updated_at""";

    private static final RowMapper<Payment> MAPPER = (rs, n) -> new Payment(
            rs.getObject("id", UUID.class),
            PaymentKind.valueOf(rs.getString("kind")),
            PaymentStatus.valueOf(rs.getString("status")),
            rs.getObject("initiator_user_id", UUID.class),
            rs.getObject("payer_account_id", UUID.class),
            rs.getObject("payee_account_id", UUID.class),
            rs.getString("destination_ref"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("reference"),
            rs.getString("failure_reason"),
            rs.getInt("sequence"),
            rs.getString("rail_reference"),
            rs.getInt("rail_attempts"),
            Times.read(rs, "rail_next_attempt_at"),
            Times.read(rs, "created_at"),
            Times.read(rs, "updated_at"));

    private final JdbcClient jdbc;
    private final Clock clock;

    public PaymentRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void insert(Payment p) {
        jdbc.sql("""
                INSERT INTO payments (id, kind, status, initiator_user_id, payer_account_id, payee_account_id,
                    destination_ref, amount_minor, currency, reference, failure_reason, sequence, rail_next_attempt_at,
                    created_at, updated_at)
                VALUES (:id, :kind, :status, :initiator, :payer, :payee, :dest, :amount, :currency, :reference,
                    :failure, :sequence, :nextAttempt, :created, :updated)""")
                .param("id", p.id()).param("kind", p.kind().name()).param("status", p.status().name())
                .param("initiator", p.initiatorUserId()).param("payer", p.payerAccountId())
                .param("payee", p.payeeAccountId()).param("dest", p.destinationRef())
                .param("amount", p.amountMinor()).param("currency", p.currency())
                .param("reference", p.reference()).param("failure", p.failureReason())
                .param("sequence", p.sequence()).param("nextAttempt", Times.toDb(p.railNextAttemptAt()))
                .param("created", Times.toDb(p.createdAt())).param("updated", Times.toDb(p.updatedAt()))
                .update();
    }

    public Optional<Payment> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<Payment> findForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE id = :id FOR UPDATE").param("id", id).query(MAPPER).optional();
    }

    /**
     * Compare-and-set transition. The WHERE clause carries the expected status, so when two actors race
     * (e.g. admin reversal vs. another reversal) exactly one UPDATE matches a row; the other gets a conflict.
     * The legality of the edge itself is decided by {@link PaymentStatus}; callers must check it first.
     */
    public Payment transition(Payment current, PaymentStatus next, String failureReason) {
        int rows = jdbc.sql("""
                UPDATE payments
                   SET status = :next, sequence = sequence + 1, failure_reason = COALESCE(:reason, failure_reason),
                       rail_next_attempt_at = CASE WHEN :next = 'SUBMITTED' THEN COALESCE(rail_next_attempt_at, :now) ELSE NULL END,
                       updated_at = :now
                 WHERE id = :id AND status = :expected""")
                .param("next", next.name()).param("reason", failureReason)
                .param("now", Times.toDb(clock.instant()))
                .param("id", current.id()).param("expected", current.status().name())
                .update();
        if (rows != 1) {
            throw DomainException.conflict("CONCURRENT_MODIFICATION",
                    "The payment was changed by another request; reload and retry");
        }
        return find(current.id()).orElseThrow();
    }

    public void recordRailAttempt(UUID id, int attempts, Instant nextAttemptAt, String railReference) {
        jdbc.sql("""
                UPDATE payments SET rail_attempts = :attempts, rail_next_attempt_at = :next,
                       rail_reference = COALESCE(:ref, rail_reference), updated_at = :now
                 WHERE id = :id AND status = 'SUBMITTED'""")
                .param("attempts", attempts).param("next", Times.toDb(nextAttemptAt)).param("ref", railReference)
                .param("now", Times.toDb(clock.instant())).param("id", id).update();
    }

    public void setRailReference(UUID id, String railReference) {
        jdbc.sql("UPDATE payments SET rail_reference = :ref WHERE id = :id")
                .param("ref", railReference).param("id", id).update();
    }

    /** Claims due payouts for processing; SKIP LOCKED lets several workers run without blocking each other. */
    public List<Payment> lockDuePayouts(Instant now, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " " + """
                 FROM payments WHERE kind = 'PAYOUT' AND status = 'SUBMITTED' AND rail_next_attempt_at <= :now
                 ORDER BY rail_next_attempt_at LIMIT :limit FOR UPDATE SKIP LOCKED""")
                .param("now", Times.toDb(now)).param("limit", limit).query(MAPPER).list();
    }

    public List<Payment> listForAccounts(List<UUID> accountIds, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + " " + """
                 FROM payments WHERE payer_account_id IN (:ids) OR payee_account_id IN (:ids)
                 ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset""")
                .param("ids", accountIds).param("limit", limit).param("offset", offset).query(MAPPER).list();
    }

    public List<Payment> listPendingReview(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE status = 'PENDING_REVIEW' ORDER BY created_at LIMIT :limit")
                .param("limit", limit).query(MAPPER).list();
    }

    public long countStuckPayouts(Instant olderThan) {
        return jdbc.sql("SELECT count(*) FROM payments WHERE kind = 'PAYOUT' AND status = 'SUBMITTED' AND created_at < :t")
                .param("t", Times.toDb(olderThan)).query(Long.class).single();
    }

}
