package io.paynexa.payments.rail;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SANDBOX ONLY. A deterministic in-memory stand-in for a payout rail; it moves no real money. Behaviour is driven by
 * the destination string so tests and demos can exercise every path:
 * <ul>
 *   <li>{@code ...-reject}  : definitively rejected</li>
 *   <li>{@code ...-pending} : stays pending for the first {@link #PENDING_CALLS} submits, then settles</li>
 *   <li>{@code ...-down}    : rail unreachable (throws)</li>
 *   <li>anything else       : settles immediately</li>
 * </ul>
 * It is idempotent per idempotency key, like a well-behaved real rail.
 */
public class SimulatedRailClient implements RailClient {

    public static final int PENDING_CALLS = 2;

    private final Map<String, Result> final_ = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    public final AtomicInteger totalCalls = new AtomicInteger();

    @Override
    public Result submit(Instruction in) throws RailUnavailableException {
        totalCalls.incrementAndGet();
        var already = final_.get(in.idempotencyKey());
        if (already != null) {
            return already;
        }
        var dest = in.destination();
        if (dest.endsWith("-down")) {
            throw new RailUnavailableException("simulated rail outage");
        }
        var ref = "sim-" + in.idempotencyKey();
        Result result;
        if (dest.endsWith("-reject")) {
            result = new Result(Outcome.REJECTED, ref, "simulated rejection");
        } else if (dest.endsWith("-pending")
                && calls.computeIfAbsent(in.idempotencyKey(), k -> new AtomicInteger()).incrementAndGet() <= PENDING_CALLS) {
            return new Result(Outcome.PENDING, ref, "simulated pending");
        } else {
            result = new Result(Outcome.SETTLED, ref, "simulated settlement");
        }
        final_.put(in.idempotencyKey(), result);
        return result;
    }
}
