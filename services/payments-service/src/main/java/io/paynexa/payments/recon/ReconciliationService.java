package io.paynexa.payments.recon;

import io.micrometer.core.instrument.MeterRegistry;
import io.paynexa.payments.config.PaymentsProperties;
import io.paynexa.payments.persistence.OutboxRepository;
import io.paynexa.payments.recon.Finding.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Independent re-derivation of financial truth. Every check recomputes from the immutable postings and compares with
 * what other tables claim. Reports only: it never edits money. CRITICAL findings mean the books cannot be trusted
 * (stop, investigate, follow docs/ops/RUNBOOKS.md); WARNING means a process is late or stuck.
 *
 * <p>The checks run in one REPEATABLE READ read-only transaction so they see a single consistent snapshot while
 * payments continue.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public record Report(UUID runId, Instant startedAt, Instant finishedAt, List<Finding> findings) {
        public boolean clean() {
            return findings.isEmpty();
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PaymentsProperties props;
    private final OutboxRepository outbox;
    private final Clock clock;
    private final MeterRegistry meters;
    private final AtomicInteger lastCritical = new AtomicInteger();
    private final AtomicInteger lastWarning = new AtomicInteger();

    public ReconciliationService(JdbcClient jdbc, TransactionTemplate tx, PaymentsProperties props,
                                 OutboxRepository outbox, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
        this.outbox = outbox;
        this.clock = clock;
        this.meters = meters;
    }

    /** Registers gauges; called once by the Spring wiring (kept out of the constructor on purpose). */
    public void bindMetrics() {
        meters.gauge("paynexa.reconciliation.findings", List.of(io.micrometer.core.instrument.Tag.of("severity", "critical")), lastCritical);
        meters.gauge("paynexa.reconciliation.findings", List.of(io.micrometer.core.instrument.Tag.of("severity", "warning")), lastWarning);
    }

    public Report run(String trigger) {
        var started = clock.instant();
        var findings = new ArrayList<Finding>();
        var snapshot = new TransactionTemplate(tx.getTransactionManager());
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionTemplate.ISOLATION_REPEATABLE_READ);
        snapshot.executeWithoutResult(s -> {
            unbalancedEntries(findings);
            balanceProjectionDrift(findings);
            currencySums(findings);
            completedWithoutEntries(findings);
            entriesWithoutExpectedStatus(findings);
            payoutClearingMismatch(findings);
            stuckPayouts(findings);
            outboxProblems(findings);
            completedWithoutEvent(findings);
        });
        var finished = clock.instant();
        var runId = UUID.randomUUID();
        persist(runId, started, finished, trigger, findings);
        lastCritical.set((int) findings.stream().filter(f -> f.severity() == Severity.CRITICAL).count());
        lastWarning.set((int) findings.stream().filter(f -> f.severity() == Severity.WARNING).count());
        if (lastCritical.get() > 0) {
            log.error("RECONCILIATION found {} CRITICAL finding(s); run={}", lastCritical.get(), runId);
        }
        return new Report(runId, started, finished, List.copyOf(findings));
    }

    // ------------------------------------------------------------------------------------------------ checks

    /** Every journal entry has >= 2 postings and debits = credits in every currency. */
    private void unbalancedEntries(List<Finding> out) {
        jdbc.sql("""
                SELECT e.id, e.payment_id, COALESCE(p.currency, '-') AS currency,
                       COUNT(p.id) AS legs,
                       COALESCE(SUM(CASE p.direction WHEN 'D' THEN p.amount_minor ELSE -p.amount_minor END), 0) AS imbalance
                  FROM journal_entries e LEFT JOIN postings p ON p.entry_id = e.id
                 GROUP BY e.id, e.payment_id, p.currency
                HAVING COUNT(p.id) < 2 OR COALESCE(SUM(CASE p.direction WHEN 'D' THEN p.amount_minor ELSE -p.amount_minor END), 0) <> 0""")
                .query((rs, n) -> new Finding("UNBALANCED_ENTRY", Severity.CRITICAL, "entry:" + rs.getObject("id"),
                        "payment=%s currency=%s legs=%d imbalance=%d".formatted(rs.getObject("payment_id"),
                                rs.getString("currency"), rs.getInt("legs"), rs.getLong("imbalance"))))
                .list().forEach(out::add);
    }

    /** Stored customer balance equals the balance derived from postings. */
    private void balanceProjectionDrift(List<Finding> out) {
        jdbc.sql("""
                SELECT a.id, a.balance_minor,
                       COALESCE(SUM(CASE p.direction WHEN 'C' THEN p.amount_minor ELSE -p.amount_minor END), 0) AS derived
                  FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
                 WHERE a.type = 'CUSTOMER'
                 GROUP BY a.id, a.balance_minor
                HAVING a.balance_minor <> COALESCE(SUM(CASE p.direction WHEN 'C' THEN p.amount_minor ELSE -p.amount_minor END), 0)""")
                .query((rs, n) -> new Finding("BALANCE_DRIFT", Severity.CRITICAL, "account:" + rs.getObject("id"),
                        "stored=%d ledger=%d".formatted(rs.getLong("balance_minor"), rs.getLong("derived"))))
                .list().forEach(out::add);
    }

    /** Per currency, all postings together must sum to zero: money is neither created nor destroyed. */
    private void currencySums(List<Finding> out) {
        jdbc.sql("""
                SELECT currency, SUM(CASE direction WHEN 'D' THEN amount_minor ELSE -amount_minor END) AS net
                  FROM postings GROUP BY currency
                HAVING SUM(CASE direction WHEN 'D' THEN amount_minor ELSE -amount_minor END) <> 0""")
                .query((rs, n) -> new Finding("CURRENCY_NOT_ZERO_SUM", Severity.CRITICAL, "currency:" + rs.getString("currency"),
                        "net=%d".formatted(rs.getLong("net"))))
                .list().forEach(out::add);
    }

    /** A payment whose status says money moved must have the matching journal entry. */
    private void completedWithoutEntries(List<Finding> out) {
        jdbc.sql("""
                SELECT p.id, p.kind, p.status FROM payments p
                 WHERE (p.status = 'COMPLETED' AND p.kind = 'TRANSFER'
                          AND NOT EXISTS (SELECT 1 FROM journal_entries e WHERE e.payment_id = p.id AND e.kind = 'TRANSFER'))
                    OR (p.status = 'COMPLETED' AND p.kind = 'FUNDING'
                          AND NOT EXISTS (SELECT 1 FROM journal_entries e WHERE e.payment_id = p.id AND e.kind = 'FUNDING'))
                    OR (p.status = 'SUBMITTED'
                          AND NOT EXISTS (SELECT 1 FROM journal_entries e WHERE e.payment_id = p.id AND e.kind = 'PAYOUT_SUBMIT'))
                    OR (p.status = 'SETTLED'
                          AND NOT EXISTS (SELECT 1 FROM journal_entries e WHERE e.payment_id = p.id AND e.kind = 'PAYOUT_SETTLE'))
                    OR (p.status = 'REVERSED' AND p.kind = 'TRANSFER'
                          AND NOT EXISTS (SELECT 1 FROM journal_entries e WHERE e.payment_id = p.id AND e.kind = 'REVERSAL'))
                    OR (p.status = 'REVERSED' AND p.kind = 'PAYOUT'
                          AND NOT EXISTS (SELECT 1 FROM journal_entries e WHERE e.payment_id = p.id AND e.kind = 'PAYOUT_RETURN'))""")
                .query((rs, n) -> new Finding("STATUS_WITHOUT_ENTRY", Severity.CRITICAL, "payment:" + rs.getObject("id"),
                        "status %s (%s) has no matching journal entry".formatted(rs.getString("status"), rs.getString("kind"))))
                .list().forEach(out::add);
    }

    /** The reverse: money moved on the books for a payment that did not end up in a status that explains it. */
    private void entriesWithoutExpectedStatus(List<Finding> out) {
        jdbc.sql("""
                SELECT e.payment_id, e.kind, p.status FROM journal_entries e JOIN payments p ON p.id = e.payment_id
                 WHERE (e.kind = 'TRANSFER' AND p.status NOT IN ('COMPLETED', 'REVERSED'))
                    OR (e.kind = 'PAYOUT_SUBMIT' AND p.status NOT IN ('SUBMITTED', 'SETTLED', 'REVERSED'))
                    OR (e.kind = 'PAYOUT_SETTLE' AND p.status <> 'SETTLED')
                    OR (e.kind = 'PAYOUT_RETURN' AND p.status <> 'REVERSED')
                    OR (e.kind = 'REVERSAL' AND p.status <> 'REVERSED')""")
                .query((rs, n) -> new Finding("ENTRY_WITHOUT_STATUS", Severity.CRITICAL, "payment:" + rs.getObject("payment_id"),
                        "entry %s exists but payment is %s".formatted(rs.getString("kind"), rs.getString("status"))))
                .list().forEach(out::add);
    }

    /** Clearing must hold exactly the sum of payouts that are SUBMITTED (in flight), per currency. */
    private void payoutClearingMismatch(List<Finding> out) {
        jdbc.sql("""
                WITH clearing AS (
                    SELECT a.currency, COALESCE(SUM(CASE p.direction WHEN 'C' THEN p.amount_minor ELSE -p.amount_minor END), 0) AS held
                      FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
                     WHERE a.type = 'PAYOUT_CLEARING' GROUP BY a.currency),
                in_flight AS (
                    SELECT currency, SUM(amount_minor) AS expected FROM payments
                     WHERE kind = 'PAYOUT' AND status = 'SUBMITTED' GROUP BY currency)
                SELECT COALESCE(c.currency, f.currency) AS currency, COALESCE(c.held, 0) AS held, COALESCE(f.expected, 0) AS expected
                  FROM clearing c FULL JOIN in_flight f ON f.currency = c.currency
                 WHERE COALESCE(c.held, 0) <> COALESCE(f.expected, 0)""")
                .query((rs, n) -> new Finding("PAYOUT_CLEARING_MISMATCH", Severity.CRITICAL, "currency:" + rs.getString("currency"),
                        "clearing holds %d but submitted payouts total %d".formatted(rs.getLong("held"), rs.getLong("expected"))))
                .list().forEach(out::add);
    }

    private void stuckPayouts(List<Finding> out) {
        var cutoff = clock.instant().minus(props.rail().stuckAfter());
        jdbc.sql("SELECT id, rail_attempts, created_at FROM payments WHERE kind = 'PAYOUT' AND status = 'SUBMITTED' AND created_at < :t")
                .param("t", java.time.OffsetDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC))
                .query((rs, n) -> new Finding("PAYOUT_STUCK", Severity.WARNING, "payment:" + rs.getObject("id"),
                        "SUBMITTED since %s after %d rail attempts; not confirmed by the rail".formatted(rs.getObject("created_at"), rs.getInt("rail_attempts"))))
                .list().forEach(out::add);
    }

    private void outboxProblems(List<Finding> out) {
        long dead = outbox.count("DEAD");
        if (dead > 0) {
            out.add(new Finding("OUTBOX_DEAD_EVENTS", Severity.WARNING, "outbox", dead + " event(s) exhausted retries and need an operator"));
        }
        long stale = outbox.countPendingOlderThan(clock.instant().minus(props.reconciliation().outboxStaleAfter()));
        if (stale > 0) {
            out.add(new Finding("OUTBOX_STALE", Severity.WARNING, "outbox", stale + " pending event(s) older than " + props.reconciliation().outboxStaleAfter()));
        }
    }

    /** Every state a payment has been in must have produced an outbox event (published, pending or dead). */
    private void completedWithoutEvent(List<Finding> out) {
        jdbc.sql("""
                SELECT h.payment_id, h.to_status FROM payment_status_history h
                 WHERE NOT EXISTS (SELECT 1 FROM outbox_events o WHERE o.aggregate_id = h.payment_id
                                      AND o.payload->>'status' = h.to_status)""")
                .query((rs, n) -> new Finding("MISSING_EVENT", Severity.CRITICAL, "payment:" + rs.getObject("payment_id"),
                        "transition to %s has no outbox event".formatted(rs.getString("to_status"))))
                .list().forEach(out::add);
    }

    private void persist(UUID runId, Instant started, Instant finished, String trigger, List<Finding> findings) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("INSERT INTO reconciliation_runs (id, started_at, finished_at, trigger, finding_count) VALUES (:id, :s, :f, :t, :c)")
                    .param("id", runId).param("s", java.time.OffsetDateTime.ofInstant(started, java.time.ZoneOffset.UTC))
                    .param("f", java.time.OffsetDateTime.ofInstant(finished, java.time.ZoneOffset.UTC))
                    .param("t", trigger).param("c", findings.size()).update();
            for (var f : findings) {
                jdbc.sql("INSERT INTO reconciliation_findings (run_id, check_name, severity, subject, detail) VALUES (:r, :c, :s, :sub, :d)")
                        .param("r", runId).param("c", f.check()).param("s", f.severity().name())
                        .param("sub", f.subject()).param("d", f.detail().length() > 500 ? f.detail().substring(0, 500) : f.detail()).update();
            }
        });
    }
}
