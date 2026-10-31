package io.paynexa.identity.persistence;

import io.paynexa.identity.domain.User;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private static final String COLUMNS = "id, email, password_hash, roles, status, failed_attempts, locked_until";

    private static final RowMapper<User> MAPPER = (rs, n) -> new User(
            rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash"),
            roles(rs.getArray("roles")), "ACTIVE".equals(rs.getString("status")), rs.getInt("failed_attempts"),
            rs.getObject("locked_until", OffsetDateTime.class) == null ? null : rs.getObject("locked_until", OffsetDateTime.class).toInstant());

    private static List<String> roles(java.sql.Array array) throws SQLException {
        return Arrays.asList((String[]) array.getArray());
    }

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false if the email is already registered (decided by the unique index, so it is race-free). */
    public boolean insert(UUID id, String email, String passwordHash, List<String> roles, Instant now) {
        return jdbc.sql("""
                INSERT INTO users (id, email, password_hash, roles, created_at)
                VALUES (:id, :email, :hash, :roles, :now) ON CONFLICT (email) DO NOTHING""")
                .param("id", id).param("email", email).param("hash", passwordHash)
                .param("roles", roles.toArray(String[]::new)).param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update() == 1;
    }

    public Optional<User> findByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE email = :e").param("e", email).query(MAPPER).optional();
    }

    public Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public boolean anyAdminExists() {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE 'ADMIN' = ANY(roles))").query(Boolean.class).single());
    }

    /**
     * Atomic failure counter: the increment and the lock decision happen in one statement, so concurrent wrong guesses
     * cannot slip past the limit.
     */
    public void recordFailure(UUID id, int maxAttempts, Instant lockUntil) {
        jdbc.sql("""
                UPDATE users SET failed_attempts = failed_attempts + 1,
                       locked_until = CASE WHEN failed_attempts + 1 >= :max THEN :until ELSE locked_until END
                 WHERE id = :id""")
                .param("max", maxAttempts).param("until", OffsetDateTime.ofInstant(lockUntil, ZoneOffset.UTC)).param("id", id).update();
    }

    public void resetFailures(UUID id) {
        jdbc.sql("UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE id = :id AND (failed_attempts <> 0 OR locked_until IS NOT NULL)")
                .param("id", id).update();
    }
}
