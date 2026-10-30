package io.paynexa.payments.service;

import java.util.UUID;

/** Who is performing an operation. Names in the audit trail are user ids or system component names, never emails. */
public record Actor(String name, UUID userId, boolean admin) {

    public static Actor user(UUID userId, boolean admin) {
        return new Actor((admin ? "admin:" : "user:") + userId, userId, admin);
    }

    public static Actor system(String component) {
        return new Actor("system:" + component, null, false);
    }
}
