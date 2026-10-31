package io.paynexa.payments.db;

import io.paynexa.testsupport.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real bootstrap script (infra/postgres/01-roles-and-schemas.sql) and the real migrations under the real
 * roles, then verifies what each role can and cannot do. This is the proof of the "database access controls" claim.
 */
class DatabaseRolesTest {

    static String database;
    static DataSource admin;
    static DataSource migrator;
    static DataSource app;
    static final UUID USER = UUID.randomUUID();
    static UUID fundingAccount;
    static UUID customerAccount;

    private static DataSource as(String role, String password) {
        var ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {"localhost"});
        ds.setPortNumbers(new int[] {PostgresTestDatabase.port()});
        ds.setDatabaseName(database);
        ds.setUser(role);
        ds.setPassword(password);
        return ds;
    }

    @BeforeAll
    static void bootstrap() throws Exception {
        database = PostgresTestDatabase.createDatabase();
        admin = PostgresTestDatabase.dataSourceFor(database, "postgres", null);

        var script = Files.readString(Path.of("../../infra/postgres/01-roles-and-schemas.sql"))
                .lines().filter(l -> !l.startsWith("\\"))
                .reduce("", (a, l) -> a + l + "\n")
                .replace(":'identity_migrator_pw'", "'im'").replace(":'identity_app_pw'", "'ia'")
                .replace(":'payments_migrator_pw'", "'pm'").replace(":'payments_app_pw'", "'pa'")
                .replace(":'notification_migrator_pw'", "'nm'").replace(":'notification_app_pw'", "'na'");
        try (var c = admin.getConnection(); var st = c.createStatement()) {
            // Roles are cluster-wide and the embedded server is shared: drop leftovers from an earlier run in this JVM.
            for (var role : new String[] {"identity_migrator", "identity_app", "payments_migrator", "payments_app", "notification_migrator", "notification_app"}) {
                st.execute("DROP ROLE IF EXISTS " + role);
            }
            st.execute(script);
        }

        migrator = as("payments_migrator", "pm");
        Flyway.configure().dataSource(migrator).schemas("payments").defaultSchema("payments")
                .locations("classpath:db/migration").load().migrate();
        app = as("payments_app", "pa");

        // seed through the application role, exactly as the service would
        fundingAccount = insertAccount("FUNDING_SOURCE", null);
        customerAccount = insertAccount("CUSTOMER", USER);
    }

    static UUID insertAccount(String type, UUID owner) throws SQLException {
        var id = UUID.randomUUID();
        try (var c = app.getConnection(); var ps = c.prepareStatement(
                "INSERT INTO accounts (id, type, owner_user_id, currency) VALUES (?, ?, ?, 'USD')")) {
            ps.setObject(1, id);
            ps.setString(2, type);
            ps.setObject(3, owner);
            ps.executeUpdate();
        }
        return id;
    }

    static void exec(DataSource ds, String sql) throws SQLException {
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    void theRuntimeRoleCanPostAFullPaymentAndTheBalanceTriggerStillWorksUnderIt() throws Exception {
        var payment = UUID.randomUUID();
        var entry = UUID.randomUUID();
        try (var c = app.getConnection()) {
            c.setAutoCommit(false);
            exec1(c, "INSERT INTO payments (id, kind, status, initiator_user_id, payer_account_id, payee_account_id, amount_minor, currency) VALUES ('"
                    + payment + "', 'FUNDING', 'COMPLETED', '" + USER + "', '" + fundingAccount + "', '" + customerAccount + "', 500, 'USD')");
            exec1(c, "INSERT INTO journal_entries (id, payment_id, kind) VALUES ('" + entry + "', '" + payment + "', 'FUNDING')");
            exec1(c, "INSERT INTO postings (entry_id, account_id, direction, amount_minor, currency) VALUES ('" + entry + "', '" + fundingAccount + "', 'D', 500, 'USD')");
            exec1(c, "INSERT INTO postings (entry_id, account_id, direction, amount_minor, currency) VALUES ('" + entry + "', '" + customerAccount + "', 'C', 500, 'USD')");
            c.commit();
        }
        try (var c = app.getConnection(); var rs = c.createStatement().executeQuery("SELECT balance_minor FROM accounts WHERE id = '" + customerAccount + "'")) {
            rs.next();
            assertThat(rs.getLong(1)).as("balance maintained by the SECURITY DEFINER trigger").isEqualTo(500);
        }
        exec(app, "UPDATE accounts SET status = 'FROZEN' WHERE id = '" + customerAccount + "'");      // allowed column
        exec(app, "UPDATE accounts SET status = 'ACTIVE' WHERE id = '" + customerAccount + "'");
    }

    private static void exec1(java.sql.Connection c, String sql) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    void theRuntimeRoleCannotRewriteHistoryEvenWithoutTheTriggers() {
        for (var sql : new String[] {
                "UPDATE postings SET amount_minor = 1",
                "DELETE FROM postings",
                "UPDATE journal_entries SET kind = 'TRANSFER'",
                "DELETE FROM journal_entries",
                "DELETE FROM audit_log",
                "UPDATE payment_status_history SET actor = 'x'",
                "TRUNCATE postings"}) {
            assertThatThrownBy(() -> exec(app, sql)).as(sql).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    @Test
    void theRuntimeRoleCannotSetABalanceDirectlyOrDeleteAccountsAndPayments() {
        assertThatThrownBy(() -> exec(app, "UPDATE accounts SET balance_minor = 999999999 WHERE id = '" + customerAccount + "'"))
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> exec(app, "DELETE FROM accounts")).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> exec(app, "DELETE FROM payments")).hasMessageContaining("permission denied");
    }

    @Test
    void theRuntimeRoleCannotChangeTheSchemaOrDisableTheGuards() {
        assertThatThrownBy(() -> exec(app, "CREATE TABLE payments.backdoor (id int)")).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> exec(app, "DROP TABLE payments.postings")).hasMessageContaining("must be owner");
        assertThatThrownBy(() -> exec(app, "ALTER TABLE payments.postings DISABLE TRIGGER ALL")).hasMessageContaining("must be owner");
        assertThatThrownBy(() -> exec(app, "DROP TRIGGER postings_append_only ON payments.postings")).hasMessageContaining("must be owner");
        assertThatThrownBy(() -> exec(app, "SET session_replication_role = replica")).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> exec(app, "CREATE TABLE public.x (id int)")).hasMessageContaining("permission denied");
    }

    @Test
    void theRuntimeRoleCannotReachOtherServicesSchemas() {
        assertThatThrownBy(() -> exec(app, "SELECT * FROM identity.users")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec(app, "SELECT * FROM notifications.inbox")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec(app, "CREATE SCHEMA evil")).hasMessageContaining("permission denied");
    }

    @Test
    void theRuntimeRoleIsNotASuperuserAndCannotEscalate() {
        assertThatThrownBy(() -> exec(app, "CREATE ROLE intruder SUPERUSER")).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> exec(app, "ALTER ROLE payments_app SUPERUSER")).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> exec(app, "GRANT payments_migrator TO payments_app")).isInstanceOf(SQLException.class);
    }

    @Test
    void onlyTheMigratorOwnsTheTablesAndRuntimeLimitsApply() throws Exception {
        try (var c = admin.getConnection(); var rs = c.createStatement().executeQuery(
                "SELECT count(*) FROM pg_tables WHERE schemaname = 'payments' AND tableowner <> 'payments_migrator'")) {
            rs.next();
            assertThat(rs.getLong(1)).as("tables not owned by the migrator").isZero();
        }
        try (var c = app.getConnection(); var rs = c.createStatement().executeQuery("SHOW statement_timeout")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("15s");
        }
    }
}
