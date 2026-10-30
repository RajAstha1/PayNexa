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

    /** Creates a new empty database; returns its name. */
    public static String createDatabase() {
        var name = "t" + COUNTER.incrementAndGet() + "_" + Long.toHexString(System.nanoTime());
        try (var c = server().getPostgresDatabase().getConnection(); var st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return name;
    }

    public static DataSource emptyDatabase() {
        return dataSourceFor(createDatabase(), "postgres", null);
    }

    /**
     * New database with {@code schema} created and the Flyway migrations from {@code locations} applied. The returned
     * DataSource has the schema on its search_path, exactly like the services' production configuration.
     */
    public static DataSource migrated(String schema, String... locations) {
        var name = createDatabase();
        Flyway.configure()
                .dataSource(dataSourceFor(name, "postgres", null))
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations(locations)
                .load()
                .migrate();
        return dataSourceFor(name, "postgres", schema);
    }

    public static DataSource dataSourceFor(String database, String user, String currentSchema) {
        var ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {"localhost"});
        ds.setPortNumbers(new int[] {server().getPort()});
        ds.setDatabaseName(database);
        ds.setUser(user);
        ds.setPassword("postgres");
        if (currentSchema != null) {
            ds.setCurrentSchema(currentSchema);
        }
        return ds;
    }

    public static int port() {
        return server().getPort();
    }
}
