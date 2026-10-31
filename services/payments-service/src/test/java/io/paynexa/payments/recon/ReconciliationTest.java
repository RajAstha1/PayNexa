package io.paynexa.payments.recon;

import io.paynexa.payments.domain.Money;
import io.paynexa.payments.outbox.OutboxRelay;
import io.paynexa.payments.rail.PayoutWorker;
import io.paynexa.payments.rail.SimulatedRailClient;
import io.paynexa.payments.recon.Finding.Severity;
import io.paynexa.payments.service.PaymentService.PayoutCommand;
import io.paynexa.payments.service.PaymentService.TransferCommand;
import io.paynexa.payments.support.RecordingPublisher;
import io.paynexa.payments.support.TestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Each test gets its own database because the tests deliberately corrupt it. */
class ReconciliationTest {

    TestKit kit;
    ReconciliationService recon;

    @BeforeEach
    void setUp() {
        kit = TestKit.create();
        recon = new ReconciliationService(kit.jdbc, kit.tx, kit.props, kit.outbox, kit.clock, kit.meters);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    /** A realistic mix: funding, transfers, a reversal, a settled payout and a returned payout. All events published. */
    private void realisticActivity() {
        var alice = kit.newUser();
        var bob = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "500.00");
        var b = kit.fundedAccount(bob, "USD", "50.00");
        kit.paymentService.transfer(alice, key(), new TransferCommand(a.id(), b.id(), Money.parse("20.00", "USD"), null));
        var toReverse = kit.paymentService.transfer(alice, key(), new TransferCommand(a.id(), b.id(), Money.parse("5.00", "USD"), null)).payment();
        kit.lifecycle.reverse(kit.newAdmin(), toReverse.id(), "test");
        kit.paymentService.payout(alice, key(), new PayoutCommand(a.id(), "bank:ok-1", Money.parse("10.00", "USD"), null));
        kit.paymentService.payout(alice, key(), new PayoutCommand(a.id(), "bank:acct-reject", Money.parse("11.00", "USD"), null));
        new PayoutWorker(kit.tx, kit.payments, kit.lifecycle, new SimulatedRailClient(), kit.props, kit.clock, kit.meters).runOnce();
        new OutboxRelay(kit.tx, kit.outbox, new RecordingPublisher(), kit.props, kit.clock, kit.meters).runOnce();
    }

    /** Bypasses every trigger and constraint-by-trigger, as a faulty restore or a manual fix by a DBA could. */
    private void tamper(String... statements) throws Exception {
        try (var c = kit.ds.getConnection(); var st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            try {
                for (var sql : statements) {
                    st.execute(sql);
                }
            } finally {
                st.execute("RESET session_replication_role");
            }
        }
    }

    private boolean has(ReconciliationService.Report r, String check, Severity severity) {
        return r.findings().stream().anyMatch(f -> f.check().equals(check) && f.severity() == severity);
    }

    @Test
    void healthySystemAfterRealisticActivityHasNoFindings() {
        realisticActivity();
        var report = recon.run("test");
        assertThat(report.findings()).isEmpty();
        assertThat(report.clean()).isTrue();
        assertThat(kit.count("SELECT count(*) FROM reconciliation_runs")).isEqualTo(1);
    }

    @Test
    void detectsBalanceProjectionDrift() throws Exception {
        realisticActivity();
        tamper("UPDATE accounts SET balance_minor = balance_minor + 1 WHERE type = 'CUSTOMER' AND balance_minor > 0");
        var report = recon.run("test");
        assertThat(has(report, "BALANCE_DRIFT", Severity.CRITICAL)).isTrue();
        assertThat(kit.count("SELECT count(*) FROM reconciliation_findings WHERE check_name = 'BALANCE_DRIFT'")).isGreaterThan(0);
    }

    @Test
    void detectsAnUnbalancedJournalEntryAndMoneyCreatedFromNothing() throws Exception {
        realisticActivity();
        var victim = kit.jdbc.sql("SELECT id FROM accounts WHERE type = 'CUSTOMER' LIMIT 1").query(UUID.class).single();
        var entry = kit.jdbc.sql("SELECT id FROM journal_entries LIMIT 1").query(UUID.class).single();
        tamper("INSERT INTO postings (entry_id, account_id, direction, amount_minor, currency) VALUES ('"
                + entry + "', '" + victim + "', 'C', 777, 'USD')");
        var report = recon.run("test");
        assertThat(has(report, "UNBALANCED_ENTRY", Severity.CRITICAL)).isTrue();
        assertThat(has(report, "CURRENCY_NOT_ZERO_SUM", Severity.CRITICAL)).isTrue();
        assertThat(has(report, "BALANCE_DRIFT", Severity.CRITICAL)).isTrue(); // projection was not updated by the forged posting
    }

    @Test
    void detectsCompletedPaymentWithNoJournalEntry() throws Exception {
        realisticActivity();
        var ghost = UUID.randomUUID();
        var payer = kit.jdbc.sql("SELECT id FROM accounts WHERE type = 'CUSTOMER' ORDER BY id LIMIT 1").query(UUID.class).single();
        var payee = kit.jdbc.sql("SELECT id FROM accounts WHERE type = 'CUSTOMER' ORDER BY id DESC LIMIT 1").query(UUID.class).single();
        tamper("INSERT INTO payments (id, kind, status, initiator_user_id, payer_account_id, payee_account_id, amount_minor, currency) VALUES ('"
                + ghost + "', 'TRANSFER', 'COMPLETED', '" + UUID.randomUUID() + "', '" + payer + "', '" + payee + "', 900, 'USD')");
        var report = recon.run("test");
        assertThat(report.findings()).anyMatch(f -> f.check().equals("STATUS_WITHOUT_ENTRY") && f.subject().equals("payment:" + ghost));
    }

    @Test
    void detectsAJournalEntryWhosePaymentIsNotInAMatchingState() throws Exception {
        realisticActivity();
        var completed = kit.jdbc.sql("SELECT id FROM payments WHERE kind = 'TRANSFER' AND status = 'COMPLETED' LIMIT 1").query(UUID.class).single();
        tamper("UPDATE payments SET status = 'FAILED' WHERE id = '" + completed + "'");
        var report = recon.run("test");
        assertThat(report.findings()).anyMatch(f -> f.check().equals("ENTRY_WITHOUT_STATUS") && f.subject().equals("payment:" + completed));
    }

    @Test
    void detectsPayoutClearingThatDoesNotMatchInFlightPayouts() throws Exception {
        var user = kit.newUser();
        var acc = kit.fundedAccount(user, "USD", "100.00");
        var p = kit.paymentService.payout(user, key(), new PayoutCommand(acc.id(), "bank:acct-down", Money.parse("30.00", "USD"), null)).payment();
        assertThat(recon.run("test").findings()).as("in-flight payout is healthy").noneMatch(f -> f.severity() == Severity.CRITICAL);

        tamper("UPDATE payments SET status = 'FAILED' WHERE id = '" + p.id() + "'"); // money still sits in clearing
        var report = recon.run("test");
        assertThat(has(report, "PAYOUT_CLEARING_MISMATCH", Severity.CRITICAL)).isTrue();
    }

    @Test
    void detectsATransitionWhoseEventWasLost() throws Exception {
        realisticActivity();
        tamper("DELETE FROM outbox_events WHERE id = (SELECT min(id) FROM outbox_events WHERE event_type = 'payment.completed')");
        var report = recon.run("test");
        assertThat(has(report, "MISSING_EVENT", Severity.CRITICAL)).isTrue();
    }

    @Test
    void warnsAboutPayoutsTheRailNeverConfirmed() {
        var user = kit.newUser();
        var acc = kit.fundedAccount(user, "USD", "100.00");
        var p = kit.paymentService.payout(user, key(), new PayoutCommand(acc.id(), "bank:acct-down", Money.parse("10.00", "USD"), null)).payment();
        assertThat(recon.run("test").findings()).noneMatch(f -> f.check().equals("PAYOUT_STUCK"));

        kit.clock.advance(Duration.ofHours(2));                // stuckAfter = 1h
        var report = recon.run("test");

        assertThat(report.findings()).anyMatch(f -> f.check().equals("PAYOUT_STUCK") && f.severity() == Severity.WARNING
                && f.subject().equals("payment:" + p.id()));
        assertThat(report.findings()).noneMatch(f -> f.severity() == Severity.CRITICAL && !f.check().equals("MISSING_EVENT"));
    }

    @Test
    void warnsAboutStaleAndDeadOutboxEvents() {
        var user = kit.newUser();
        kit.fundedAccount(user, "USD", "10.00");
        kit.clock.advance(Duration.ofMinutes(30));              // outboxStaleAfter = 15m, nothing published
        assertThat(recon.run("test").findings()).anyMatch(f -> f.check().equals("OUTBOX_STALE"));

        kit.jdbc.sql("UPDATE outbox_events SET status = 'DEAD'").update();
        assertThat(recon.run("test").findings()).anyMatch(f -> f.check().equals("OUTBOX_DEAD_EVENTS"));
    }

    @Test
    void reconciliationIsReadOnlyWithRespectToMoney() {
        realisticActivity();
        long before = kit.count("SELECT COALESCE(SUM(balance_minor),0) FROM accounts");
        long postings = kit.count("SELECT count(*) FROM postings");
        recon.run("test");
        assertThat(kit.count("SELECT COALESCE(SUM(balance_minor),0) FROM accounts")).isEqualTo(before);
        assertThat(kit.count("SELECT count(*) FROM postings")).isEqualTo(postings);
    }
}
