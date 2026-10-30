package io.paynexa.payments.rail;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.paynexa.payments.config.PaymentsProperties;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wraps any {@link RailClient} with a hard timeout and a circuit breaker. When the breaker is open calls fail
 * immediately (so a dead rail does not tie up worker threads) with {@link RailUnavailableException}, which the worker
 * treats as "try again later", never as success or failure of the payout.
 */
public final class ResilientRailClient implements RailClient {

    private final RailClient delegate;
    private final CircuitBreaker breaker;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ResilientRailClient(RailClient delegate, PaymentsProperties.Rail config, CircuitBreaker breaker) {
        this.delegate = delegate;
        this.timeout = config.callTimeout();
        this.breaker = breaker;
    }

    public static CircuitBreaker defaultBreaker() {
        return CircuitBreaker.of("payout-rail", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(RailUnavailableException.class)
                .build());
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    @Override
    public Result submit(Instruction instruction) throws RailUnavailableException {
        try {
            return breaker.executeCallable(() -> callWithTimeout(instruction));
        } catch (CallNotPermittedException e) {
            throw new RailUnavailableException("rail circuit breaker is open");
        } catch (RailUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new RailUnavailableException("rail call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private Result callWithTimeout(Instruction instruction) throws Exception {
        Future<Result> future = executor.submit(() -> delegate.submit(instruction));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RailUnavailableException("rail call timed out after " + timeout);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RailUnavailableException rue) {
                throw rue;
            }
            throw new RailUnavailableException("rail call failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RailUnavailableException("interrupted while calling rail", e);
        }
    }
}
