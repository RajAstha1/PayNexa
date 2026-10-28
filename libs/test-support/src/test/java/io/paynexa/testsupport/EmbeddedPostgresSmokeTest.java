package io.paynexa.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddedPostgresSmokeTest {

    @Test
    void realPostgres17AppliesFlywayMigrationAndEnforcesConstraints() throws Exception {
        try (var pg = EmbeddedPostgres.builder().start()) {
            var ds = pg.getPostgresDatabase();
            try (var c = ds.getConnection(); var rs = c.createStatement().executeQuery("select version()")) {
                rs.next();
                assertThat(rs.getString(1)).startsWith("PostgreSQL 17.");
            }
            var result = Flyway.configure().dataSource(ds).locations("classpath:db/smoke").load().migrate();
            assertThat(result.migrationsExecuted).isEqualTo(1);
            try (var c = ds.getConnection()) {
                assertThatThrownBy(() -> c.createStatement().executeUpdate("insert into smoke(amount) values (0)"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("check constraint");
            }
        }
    }
}
