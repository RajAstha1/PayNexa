package io.paynexa.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real PostgreSQL for tests, without Docker. One server is started lazily per JVM and shared; every call to
 * {@link #migrated} creates an isolated, freshly migrated database so test classes cannot affect each other.
 */
public final class PostgresTestDatabase {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static volatile EmbeddedPostgres server;

    private PostgresTestDatabase() {}

    private static EmbeddedPostgres server() {
        if (server == null) {
            synchronized (PostgresTestDatabase.class) {
                if (server == null) {
                    try {
                        var started = EmbeddedPostgres.builder()
                                .setServerConfig("max_connections", "200")
                                .setServerConfig("fsync", "off") // test speed only; never used outside tests
                                .start();
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            try {
                                started.close();
                            } catch (IOException ignored) {
                                // best effort at JVM shutdown
                            }
                        }));
                        server = started;
                    } catch (IOException e) {
                        throw new IllegalStateException("Could not start embedded PostgreSQL", e);
                    }
                }
            }
        }
        return server;
    }

    /** Creates a new empty database and returns a pooled-free DataSource connected to it (superuser). */
    public static DataSource emptyDatabase() {
        var name = "t" + COUNTER.incrementAndGet() + "_" + Long.toHexString(System.nanoTime());
        try (var c = server().getPostgresDatabase().getConnection(); var st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return dataSourceFor(name, "postgres");
    }

    /** New database with {@code schema} created and Flyway migrations from {@code locations} applied into it. */
    public static DataSource migrated(String schema, String... locations) {
        var ds = emptyDatabase();
        Flyway.configure()
                .dataSource(ds)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations(locations)
                .load()
                .migrate();
        return ds;
    }

    public static DataSource dataSourceFor(String database, String user) {
        var ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {"localhost"});
        ds.setPortNumbers(new int[] {server().getPort()});
        ds.setDatabaseName(database);
        ds.setUser(user);
        ds.setPassword("postgres");
        return ds;
    }

    public static int port() {
        return server().getPort();
    }
}
