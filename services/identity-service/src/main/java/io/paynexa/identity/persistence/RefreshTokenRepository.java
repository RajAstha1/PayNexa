package io.paynexa.identity.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RefreshTokenRepository {

    public record Row(UUID id, UUID userId, UUID familyId, Instant expiresAt, boolean revoked, String revokeReason) {}

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID userId, UUID familyId, String tokenHash, Instant now, Instant expires) {
        jdbc.sql("INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, created_at, expires_at) VALUES (:id, :u, :f, :h, :c, :e)")
                .param("id", id).param("u", userId).param("f", familyId).param("h", tokenHash)
                .param("c", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).param("e", OffsetDateTime.ofInstant(expires, ZoneOffset.UTC)).update();
    }

    /** Row-locks the token so two concurrent refreshes with the same token are serialised. */
    public Optional<Row> lockByHash(String tokenHash) {
        return jdbc.sql("SELECT id, user_id, family_id, expires_at, revoked_at, revoke_reason FROM refresh_tokens WHERE token_hash = :h FOR UPDATE")
                .param("h", tokenHash)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class), rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("revoked_at") != null, rs.getString("revoke_reason")))
                .optional();
    }

    public void revoke(UUID id, String reason, Instant now) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = :n, revoke_reason = :r WHERE id = :id AND revoked_at IS NULL")
                .param("n", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).param("r", reason).param("id", id).update();
    }

    public int revokeFamily(UUID familyId, String reason, Instant now) {
        return jdbc.sql("UPDATE refresh_tokens SET revoked_at = :n, revoke_reason = :r WHERE family_id = :f AND revoked_at IS NULL")
                .param("n", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).param("r", reason).param("f", familyId).update();
    }

    public long activeCount(UUID userId, Instant now) {
        return jdbc.sql("SELECT count(*) FROM refresh_tokens WHERE user_id = :u AND revoked_at IS NULL AND expires_at > :n")
                .param("u", userId).param("n", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).query(Long.class).single();
    }
}
