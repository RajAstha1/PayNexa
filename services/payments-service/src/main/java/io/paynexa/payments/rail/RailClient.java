package io.paynexa.payments.rail;

/**
 * Port to an external payout rail. A real implementation (bank/ACH/RTP provider) needs its own integration and
 * security review; this repository only ships {@link SimulatedRailClient}. Implementations must:
 * <ul>
 *   <li>treat {@code idempotencyKey} (the payment id) as the rail-side idempotency key, so a retried submit cannot pay twice;</li>
 *   <li>throw {@link RailUnavailableException} for anything transient (timeout, 5xx, connection error);</li>
 *   <li>return {@link Outcome#REJECTED} only for a definitive refusal.</li>
 * </ul>
 */
public interface RailClient {

    enum Outcome { SETTLED, PENDING, REJECTED }

    record Result(Outcome outcome, String railReference, String detail) {}

    record Instruction(String idempotencyKey, String destination, long amountMinor, String currency) {}

    /** Submits (or re-submits, idempotently) the instruction and reports the rail's current view. */
    Result submit(Instruction instruction) throws RailUnavailableException;

    final class RailUnavailableException extends Exception {
        public RailUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public RailUnavailableException(String message) {
            super(message);
        }
    }
}
