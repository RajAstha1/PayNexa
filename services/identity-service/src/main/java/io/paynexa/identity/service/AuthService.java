package io.paynexa.identity.service;

import io.paynexa.identity.config.IdentityProperties;
import io.paynexa.identity.domain.AuthException;
import io.paynexa.identity.domain.PasswordPolicy;
import io.paynexa.identity.domain.User;
import io.paynexa.identity.persistence.AuditRepository;
import io.paynexa.identity.persistence.RefreshTokenRepository;
import io.paynexa.identity.persistence.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {

    public record Tokens(String accessToken, long expiresInSeconds, String refreshToken) {}

    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._%+-]{1,64}@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,24}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AuditRepository audit;
    private final PasswordEncoder encoder;
    private final TokenIssuer issuer;
    private final TransactionTemplate tx;
    private final IdentityProperties props;
    private final Clock clock;
    private final MeterRegistry meters;
    /** Verified against when the email is unknown, so unknown and wrong-password cost the same time. */
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, AuditRepository audit,
                       PasswordEncoder encoder, TokenIssuer issuer, TransactionTemplate tx, IdentityProperties props,
                       Clock clock, MeterRegistry meters) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.encoder = encoder;
        this.issuer = issuer;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
        this.dummyHash = encoder.encode("not-a-real-password-" + UUID.randomUUID());
    }

    public static String normaliseEmail(String raw) {
        var email = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new AuthException(AuthException.Kind.INVALID_INPUT, "INVALID_EMAIL", "A valid email address is required");
        }
        return email;
    }

    // ---------------------------------------------------------------------------------------------- register

    public Tokens register(String rawEmail, String password) {
        var email = normaliseEmail(rawEmail);
        PasswordPolicy.validate(password, email);
        var hash = encoder.encode(password);                       // slow hash computed outside the transaction
        var created = tx.execute(s -> {
            var id = UUID.randomUUID();
            if (!users.insert(id, email, hash, List.of("USER"), clock.instant())) {
                return null;                                     // commit nothing, decide outside the transaction
            }
            audit.record("REGISTER", id, "SUCCESS", null);
            meters.counter("paynexa.identity.registrations").increment();
            return startSession(users.findById(id).orElseThrow());
        });
        if (created == null) {
            audit.record("REGISTER", null, "FAILURE", "email already registered");   // autocommit: survives the error
            throw new AuthException(AuthException.Kind.CONFLICT, "EMAIL_TAKEN", "An account with this email already exists");
        }
        return created;
    }

    /** Used at startup for the optional sandbox administrator. */
    public void createAdminIfNoneExists(String rawEmail, String password) {
        var email = normaliseEmail(rawEmail);
        PasswordPolicy.validate(password, email);
        var hash = encoder.encode(password);
        tx.executeWithoutResult(s -> {
            if (!users.anyAdminExists()) {
                var id = UUID.randomUUID();
                if (users.insert(id, email, hash, List.of("USER", "ADMIN"), clock.instant())) {
                    audit.record("BOOTSTRAP_ADMIN", id, "SUCCESS", null);
                }
            }
        });
    }

    // -------------------------------------------------------------------------------------------------- login

    public Tokens login(String rawEmail, String password) {
        String email;
        try {
            email = normaliseEmail(rawEmail);
        } catch (AuthException e) {
            encoder.matches(password == null ? "" : password, dummyHash);
            throw AuthException.invalidCredentials();
        }
        var user = users.findByEmail(email).orElse(null);
        var now = clock.instant();
        if (user == null) {
            encoder.matches(password == null ? "" : password, dummyHash);
            audit.record("LOGIN", null, "FAILURE", "unknown account");
            meters.counter("paynexa.identity.logins", "outcome", "failure").increment();
            throw AuthException.invalidCredentials();
        }
        if (!user.active() || user.lockedAt(now)) {
            // Do not reveal lock/disabled state: same response and same cost as a wrong password.
            encoder.matches(password == null ? "" : password, dummyHash);
            audit.record("LOGIN", user.id(), "FAILURE", user.active() ? "account locked" : "account disabled");
            meters.counter("paynexa.identity.logins", "outcome", "blocked").increment();
            throw AuthException.invalidCredentials();
        }
        boolean ok = password != null && encoder.matches(password, user.passwordHash());
        if (!ok) {
            users.recordFailure(user.id(), props.lockout().maxFailedAttempts(), now.plus(props.lockout().duration()));
            audit.record("LOGIN", user.id(), "FAILURE", "bad credentials");
            meters.counter("paynexa.identity.logins", "outcome", "failure").increment();
            throw AuthException.invalidCredentials();
        }
        return tx.execute(s -> {
            users.resetFailures(user.id());
            audit.record("LOGIN", user.id(), "SUCCESS", null);
            meters.counter("paynexa.identity.logins", "outcome", "success").increment();
            return startSession(user);
        });
    }

    // ------------------------------------------------------------------------------------------------ refresh

    /**
     * Rotation with reuse detection. A refresh token works exactly once. Presenting an already-rotated token means it
     * was copied (or replayed), so the entire session family is revoked. Concurrent use of the same token is
     * serialised by a row lock: one request wins, the other is treated as reuse.
     */
    public Tokens refresh(String presented) {
        if (presented == null || presented.length() < 32 || presented.length() > 128) {
            throw AuthException.invalidRefreshToken();
        }
        var hash = sha256(presented);
        // The transaction must COMMIT even when the answer is "denied" (reuse revokes a whole family), so it returns a
        // result instead of throwing; the exception is raised only after the commit.
        RefreshResult result = tx.execute(s -> {
            var now = clock.instant();
            var row = refreshTokens.lockByHash(hash).orElse(null);
            if (row == null) {
                return RefreshResult.denied();
            }
            if (row.revoked()) {
                if ("ROTATED".equals(row.revokeReason())) {
                    int revoked = refreshTokens.revokeFamily(row.familyId(), "REUSE_DETECTED", now);
                    audit.record("REFRESH_REUSE", row.userId(), "FAILURE", "revoked " + revoked + " token(s)");
                    meters.counter("paynexa.identity.refresh_reuse_detected").increment();
                }
                return RefreshResult.denied();
            }
            if (!row.expiresAt().isAfter(now)) {
                return RefreshResult.denied();
            }
            var user = users.findById(row.userId()).filter(User::active).orElse(null);
            if (user == null) {
                return RefreshResult.denied();
            }
            refreshTokens.revoke(row.id(), "ROTATED", now);
            audit.record("REFRESH", user.id(), "SUCCESS", null);
            return new RefreshResult(issue(user, row.familyId()));
        });
        if (result == null || result.tokens() == null) {
            throw AuthException.invalidRefreshToken();
        }
        return result.tokens();
    }

    private record RefreshResult(Tokens tokens) {
        static RefreshResult denied() {
            return new RefreshResult(null);
        }
    }

    // ------------------------------------------------------------------------------------------------ logout

    /** Idempotent and silent: unknown or already-revoked tokens look the same as success. */
    public void logout(String presented) {
        if (presented == null || presented.length() < 32 || presented.length() > 128) {
            return;
        }
        tx.executeWithoutResult(s -> refreshTokens.lockByHash(sha256(presented)).ifPresent(row -> {
            refreshTokens.revokeFamily(row.familyId(), "LOGOUT", clock.instant());
            audit.record("LOGOUT", row.userId(), "SUCCESS", null);
        }));
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private Tokens startSession(User user) {
        return issue(user, UUID.randomUUID());
    }

    private Tokens issue(User user, UUID familyId) {
        var raw = new byte[32];
        RANDOM.nextBytes(raw);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        var now = clock.instant();
        refreshTokens.insert(UUID.randomUUID(), user.id(), familyId, sha256(token), now, now.plus(props.refreshTtl()));
        return new Tokens(issuer.issue(user.id(), user.roles()), issuer.ttl().toSeconds(), token);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
