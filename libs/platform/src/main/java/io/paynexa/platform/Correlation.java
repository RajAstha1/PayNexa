package io.paynexa.platform;

import org.slf4j.MDC;

import java.util.UUID;

/** Correlation id plumbing shared by services: HTTP header, log MDC key and Kafka header use the same names. */
public final class Correlation {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String KAFKA_HEADER = "correlation-id";

    private Correlation() {}

    /** Current id, or null outside a request/consumer scope. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Accepts only short, safe values from untrusted callers; otherwise generates a fresh id (prevents log injection). */
    public static String sanitize(String candidate) {
        if (candidate != null && candidate.matches("[A-Za-z0-9._-]{8,64}")) {
            return candidate;
        }
        return newId();
    }
}
