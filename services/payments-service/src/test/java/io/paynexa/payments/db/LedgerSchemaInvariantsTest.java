package io.paynexa.payments.db;

import io.paynexa.testsupport.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attacks the schema with raw SQL, bypassing all application code. Each test proves that PostgreSQL itself
 * refuses the corrupting operation, so the guarantees hold even if the Java layer has a bug.
 */
class LedgerSchemaInvariantsTest {

    static DataSource ds;
    static UUID funding, eurFunding;

    // Fresh customers per test so balances never leak between tests, whatever the execution order.
    UUID userA, userB, accA, accB, eurAcc;

    @BeforeAll
    static void migrate() throws Exception {
        ds = PostgresTestDatabase.migrated("payments", "classpath:db/migration");
        funding = account("FUNDING_SOURCE", null, "USD");
        eurFunding = account("FUNDING_SOURCE", null, "EUR");
    }

    @BeforeEach
    void freshAccounts() throws Exception {
        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
        accA = account("CUSTOMER", userA, "USD");
        accB = account("CUSTOMER", userB, "USD");
        eurAcc = account("CUSTOMER", userA, "EUR");
    }

    static UUID account(String type, UUID owner, String currency) throws SQLException {
        var id = UUID.randomUUID();
        try (var c = ds.getConnection(); var ps = c.prepareStatement(
                "INSERT INTO payments.accounts (id, type, owner_user_id, currency) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, type);
            ps.setObject(3, owner);
            ps.setString(4, currency);
            ps.executeUpdate();
        }
        return id;
    }

    UUID payment(UUID payer, UUID payee, long amount, String currency) throws SQLException {
        var id = UUID.randomUUID();
        try (var c = ds.getConnection(); var ps = c.prepareStatement("""
                INSERT INTO payments.payments (id, kind, status, initiator_user_id, payer_account_id, payee_account_id,
                                               amount_minor, currency)
                VALUES (?, 'TRANSFER', 'COMPLETED', ?, ?, ?, ?, ?)""")) {
            ps.setObject(1, id);
            ps.setObject(2, userA);
            ps.setObject(3, payer);
            ps.setObject(4, payee);
            ps.setLong(5, amount);
            ps.setString(6, currency);
            ps.executeUpdate();
        }
        return id;
    }

    /** Posts a two-leg entry in one transaction; the legs are given so tests can make them unbalanced. */
    static void post(UUID paymentId, String kind, Object[][] legs) throws SQLException {
        try (var c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                var entry = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "INSERT INTO payments.journal_entries (id, payment_id, kind) VALUES (?, ?, ?)")) {
                    ps.setObject(1, entry);
                    ps.setObject(2, paymentId);
                    ps.setString(3, kind);
                    ps.executeUpdate();
                }
                for (var leg : legs) {
                    try (var ps = c.prepareStatement("""
                            INSERT INTO payments.postings (entry_id, account_id, direction, amount_minor, currency)
                            VALUES (?, ?, ?, ?, ?)""")) {
                        ps.setObject(1, entry);
                        ps.setObject(2, leg[0]);
                        ps.setString(3, (String) leg[1]);
                        ps.setLong(4, (Long) leg[2]);
                        ps.setString(5, (String) leg[3]);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    static long balance(UUID account) throws SQLException {
        try (var c = ds.getConnection(); var ps = c.prepareStatement(
                "SELECT balance_minor FROM payments.accounts WHERE id = ?")) {
            ps.setObject(1, account);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static void exec(String sql) throws SQLException {
        try (Connection c = ds.getConnection(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    // ---- balanced entries --------------------------------------------------------------------------------

    @Test
    void balancedEntryCommitsAndMaintainsProjection() throws Exception {
        var p = payment(funding, accA, 5_000, "USD");
        post(p, "FUNDING", new Object[][] {
                {funding, "D", 5_000L, "USD"}, {accA, "C", 5_000L, "USD"}});
        assertThat(balance(accA)).isEqualTo(5_000);
        assertThat(balance(funding)).isZero(); // system accounts carry no stored projection by design
    }

    @Test
    void unbalancedEntryIsRejectedAtCommitAndNothingPersists() throws Exception {
        var p = payment(funding, accB, 1_000, "USD");
        assertThatThrownBy(() -> post(p, "FUNDING", new Object[][] {
                {funding, "D", 1_000L, "USD"}, {accB, "C", 999L, "USD"}}))
                .isInstanceOf(SQLException.class).hasMessageContaining("not balanced");
        assertThat(balance(accB)).isZero();
    }

    @Test
    void singleLeggedEntryIsRejected() throws Exception {
        var p = payment(funding, accB, 1_000, "USD");
        assertThatThrownBy(() -> post(p, "FUNDING", new Object[][] {{accB, "C", 1_000L, "USD"}}))
                .isInstanceOf(SQLException.class).hasMessageContaining("at least 2 required");
    }

    @Test
    void entryWithNoPostingsIsRejected() throws Exception {
        var p = payment(funding, accB, 1_000, "USD");
        assertThatThrownBy(() -> post(p, "FUNDING", new Object[][] {}))
                .isInstanceOf(SQLException.class).hasMessageContaining("postings");
    }

    @Test
    void onePerPaymentAndKindPreventsDuplicateFinancialEffect() throws Exception {
        var p = payment(funding, accB, 100, "USD");
        post(p, "FUNDING", new Object[][] {{funding, "D", 100L, "USD"}, {accB, "C", 100L, "USD"}});
        assertThatThrownBy(() -> post(p, "FUNDING", new Object[][] {
                {funding, "D", 100L, "USD"}, {accB, "C", 100L, "USD"}}))
                .isInstanceOf(SQLException.class).hasMessageContaining("uq_one_entry_per_kind");
        assertThat(balance(accB)).isEqualTo(100);
    }

    // ---- overdraft and currency --------------------------------------------------------------------------

    @Test
    void customerBalanceCannotGoNegativeAndFailedEntryLeavesNoTrace() throws Exception {
        var seed = payment(funding, accA, 50, "USD");
        post(seed, "FUNDING", new Object[][] {{funding, "D", 50L, "USD"}, {accA, "C", 50L, "USD"}});
        long before = balance(accA);
        var p = payment(accA, accB, before + 1, "USD");
        assertThatThrownBy(() -> post(p, "TRANSFER", new Object[][] {
                {accA, "D", before + 1, "USD"}, {accB, "C", before + 1, "USD"}}))
                .isInstanceOf(SQLException.class).hasMessageContaining("customer_balance_non_negative");
        assertThat(balance(accA)).isEqualTo(before);
    }

    @Test
    void postingCurrencyMustMatchAccountCurrency() throws Exception {
        var p = payment(funding, accB, 10, "USD");
        assertThatThrownBy(() -> post(p, "FUNDING", new Object[][] {
                {funding, "D", 10L, "USD"}, {eurAcc, "C", 10L, "USD"}}))
                .isInstanceOf(SQLException.class).hasMessageContaining("does not match account");
    }

    @Test
    void entryMustBalancePerCurrency() throws Exception {
        // 10 USD out and 10 EUR in "sums" to zero only if currencies are (wrongly) mixed.
        var p = payment(funding, accB, 10, "USD");
        assertThatThrownBy(() -> post(p, "FUNDING", new Object[][] {
                {funding, "D", 10L, "USD"}, {eurAcc, "C", 10L, "EUR"}}))
                .isInstanceOf(SQLException.class).hasMessageContaining("not balanced");
        assertThat(eurFunding).isNotNull();
    }

    // ---- immutability ------------------------------------------------------------------------------------

    @Test
    void postedLedgerRowsCannotBeUpdatedDeletedOrTruncated() throws Exception {
        var p = payment(funding, accB, 7, "USD");
        post(p, "FUNDING", new Object[][] {{funding, "D", 7L, "USD"}, {accB, "C", 7L, "USD"}});
        // Row-level guards only fire when rows exist, so make sure every attacked table has one.
        exec("INSERT INTO payments.payment_status_history (payment_id, to_status, actor) VALUES ('" + p + "', 'COMPLETED', 'test')");
        exec("INSERT INTO payments.audit_log (actor, action, target_type, target_id) VALUES ('test', 'X', 'payment', '" + p + "')");
        for (var sql : new String[] {
                "UPDATE payments.postings SET amount_minor = 1",
                "DELETE FROM payments.postings",
                "TRUNCATE payments.postings",
                "UPDATE payments.journal_entries SET kind = 'TRANSFER'",
                "DELETE FROM payments.journal_entries",
                "TRUNCATE payments.journal_entries",
                "UPDATE payments.payment_status_history SET actor = 'x'",
                "DELETE FROM payments.audit_log",
                "TRUNCATE payments.audit_log"}) {
            // TRUNCATE of a table referenced by a foreign key is refused by PostgreSQL itself before our trigger runs;
            // either way the statement must fail and the data must remain.
            assertThatThrownBy(() -> exec(sql)).as(sql).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(e.getMessage()).containsAnyOf("append-only", "cannot truncate"));
        }
    }

    @Test
    void ledgerStillIntactAfterMutationAttempts() throws Exception {
        var p = payment(funding, accB, 3, "USD");
        post(p, "FUNDING", new Object[][] {{funding, "D", 3L, "USD"}, {accB, "C", 3L, "USD"}});
        assertThatThrownBy(() -> exec("DELETE FROM payments.postings")).isInstanceOf(SQLException.class);
        try (var c = ds.getConnection(); var rs = c.createStatement().executeQuery(
                "SELECT count(*) FROM payments.postings WHERE account_id = '" + accB + "'")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(1);
        }
    }

    // ---- structural rules --------------------------------------------------------------------------------

    @Test
    void paymentCannotTransferToItself() {
        assertThatThrownBy(() -> payment(accA, accA, 10, "USD"))
                .isInstanceOf(SQLException.class).hasMessageContaining("payer_differs_from_payee");
    }

    @Test
    void amountsMustBePositive() {
        assertThatThrownBy(() -> payment(accA, accB, 0, "USD")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> payment(accA, accB, -5, "USD")).isInstanceOf(SQLException.class);
    }

    @Test
    void oneCustomerAccountPerUserAndCurrency() {
        assertThatThrownBy(() -> account("CUSTOMER", userA, "USD"))
                .isInstanceOf(SQLException.class).hasMessageContaining("uq_customer_account_per_currency");
    }

    @Test
    void customerRequiresOwnerAndSystemAccountMustNotHaveOne() {
        assertThatThrownBy(() -> account("CUSTOMER", null, "GBP")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> account("PAYOUT_CLEARING", UUID.randomUUID(), "GBP")).isInstanceOf(SQLException.class);
    }
}
