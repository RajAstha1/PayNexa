package io.paynexa.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record User(UUID id, String email, String passwordHash, List<String> roles, boolean active,
                   int failedAttempts, Instant lockedUntil) {

    public boolean lockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Never prints the hash or the email. */
    @Override
    public String toString() {
        return "User[id=" + id + "]";
    }
}
