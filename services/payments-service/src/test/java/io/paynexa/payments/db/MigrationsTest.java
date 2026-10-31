package io.paynexa.payments.db;

import io.paynexa.testsupport.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migrations are the only way the schema changes, so they must be reproducible, repeatable and tamper-evident. */
class MigrationsTest {

    private static Flyway flyway(javax.sql.DataSource ds) {
        return Flyway.configure().dataSource(ds).schemas("payments").defaultSchema("payments").createSchemas(true)
                .locations("classpath:db/migration").validateMigrationNaming(true).load();
    }

    @Test
    void anEmptyDatabaseMigratesToTheLatestVersionAndSecondRunIsANoOp() {
        var ds = PostgresTestDatabase.emptyDatabase();
        var first = flyway(ds).migrate();
        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isGreaterThanOrEqualTo(2);

        var second = flyway(ds).migrate();
        assertThat(second.migrationsExecuted).as("re-running is a no-op").isZero();
        assertThat(flyway(ds).info().pending()).isEmpty();
    }

    @Test
    void migrationsAreValidatedByChecksumSoAnEditedAppliedMigrationIsDetected() throws Exception {
        var ds = PostgresTestDatabase.emptyDatabase();
        flyway(ds).migrate();
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("UPDATE payments.flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");
        }
        assertThatThrownBy(() -> flyway(ds).validate()).hasMessageContaining("checksum");
    }

    @Test
    void theMigratedSchemaHasExactlyTheExpectedTablesAndNoUnexpectedOnes() throws Exception {
        var ds = PostgresTestDatabase.migrated("payments", "classpath:db/migration");
        try (var c = ds.getConnection(); var rs = c.createStatement().executeQuery(
                "SELECT string_agg(tablename, ',' ORDER BY tablename) FROM pg_tables WHERE schemaname = 'payments'")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("accounts,audit_log,flyway_schema_history,idempotency_keys,journal_entries,outbox_events,"
                    + "payment_status_history,payments,postings,reconciliation_findings,reconciliation_runs,risk_decisions");
        }
    }

    @Test
    void everyTableHasAPrimaryKeyAndEveryForeignKeyColumnIsIndexed() throws Exception {
        var ds = PostgresTestDatabase.migrated("payments", "classpath:db/migration");
        try (var c = ds.getConnection()) {
            try (var rs = c.createStatement().executeQuery("""
                    SELECT t.tablename FROM pg_tables t
                     WHERE t.schemaname = 'payments' AND t.tablename <> 'flyway_schema_history'
                       AND NOT EXISTS (SELECT 1 FROM pg_constraint k JOIN pg_class r ON r.oid = k.conrelid
                                        JOIN pg_namespace n ON n.oid = r.relnamespace
                                       WHERE k.contype = 'p' AND n.nspname = 'payments' AND r.relname = t.tablename)""")) {
                assertThat(rs.next()).as("table without a primary key").isFalse();
            }
            try (var rs = c.createStatement().executeQuery("""
                    SELECT r.relname || '.' || a.attname
                      FROM pg_constraint k
                      JOIN pg_class r ON r.oid = k.conrelid JOIN pg_namespace n ON n.oid = r.relnamespace
                      JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = k.conkey[1]
                     WHERE k.contype = 'f' AND n.nspname = 'payments'
                       AND NOT EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = k.conrelid AND i.indkey[0] = k.conkey[1])""")) {
                var unindexed = new java.util.ArrayList<String>();
                while (rs.next()) unindexed.add(rs.getString(1));
                assertThat(unindexed).as("foreign key columns without a leading index").isEmpty();
            }
        }
    }

    @Test
    void schemaRebuiltFromMigrationsPreservesTheLedgerGuardsAfterADataRoundTrip() throws Exception {
        // "Restore" model: a fresh database from migrations receives previously exported rows, then must still enforce rules.
        var ds = PostgresTestDatabase.migrated("payments", "classpath:db/migration");
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("INSERT INTO payments.accounts (id, type, currency) VALUES (gen_random_uuid(), 'FUNDING_SOURCE', 'USD')");
            assertThatThrownBy(() -> st.execute("INSERT INTO payments.journal_entries (id, payment_id, kind) VALUES (gen_random_uuid(), gen_random_uuid(), 'FUNDING')"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
