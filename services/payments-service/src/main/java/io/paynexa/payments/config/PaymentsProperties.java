package io.paynexa.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/** All tunables of payments-service. Limits are in major units of each currency (e.g. 1000 = 1000.00 USD). */
@ConfigurationProperties(prefix = "paynexa.payments")
public record PaymentsProperties(
        @DefaultValue Idempotency idempotency,
        @DefaultValue Risk risk,
        @DefaultValue Outbox outbox,
        @DefaultValue Rail rail,
        @DefaultValue Sandbox sandbox,
        @DefaultValue Reconciliation reconciliation,
        @DefaultValue RateLimit rateLimit) {

    public record Idempotency(@DefaultValue("7d") Duration retention) {}

    public record Limits(
            @DefaultValue("10000") BigDecimal maxTransaction,
            @DefaultValue("2500") BigDecimal reviewThreshold,
            @DefaultValue("20000") BigDecimal dailyOutflow,
            @DefaultValue("1000") BigDecimal firstPayeeReviewThreshold,
            @DefaultValue("20") int velocityMaxPayments,
            @DefaultValue("10m") Duration velocityWindow) {}

    /** {@code defaults} apply to every currency unless {@code perCurrency} overrides it. */
    public record Risk(@DefaultValue Limits defaults, @DefaultValue Map<String, Limits> perCurrency) {
        public Limits limitsFor(String currency) {
            return perCurrency.getOrDefault(currency, defaults);
        }
    }

    public record Outbox(
            @DefaultValue("200ms") Duration pollInterval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("10") int maxAttempts,
            @DefaultValue("1s") Duration baseBackoff,
            @DefaultValue("5m") Duration maxBackoff,
            @DefaultValue("5s") Duration sendTimeout,
            @DefaultValue("true") boolean relayEnabled) {}

    public record Rail(
            @DefaultValue("500ms") Duration pollInterval,
            @DefaultValue("20") int batchSize,
            @DefaultValue("2m") Duration lease,
            @DefaultValue("5s") Duration baseBackoff,
            @DefaultValue("30m") Duration maxBackoff,
            @DefaultValue("5s") Duration callTimeout,
            @DefaultValue("1h") Duration stuckAfter,
            @DefaultValue("20") int maxAttempts,
            @DefaultValue("true") boolean workerEnabled) {}

    /** Synthetic-money helpers. Must stay disabled anywhere real value could be affected. */
    public record Sandbox(@DefaultValue("false") boolean fundingEnabled) {}

    public record Reconciliation(
            @DefaultValue("5m") Duration interval,
            @DefaultValue("15m") Duration outboxStaleAfter,
            @DefaultValue("true") boolean scheduled) {}

    /** Per-instance token buckets; see TokenBucketRateLimiter for what that does and does not protect. */
    public record RateLimit(
            @DefaultValue("20") int paymentsBurst,
            @DefaultValue("2") double paymentsPerSecond,
            @DefaultValue("120") int generalBurst,
            @DefaultValue("20") double generalPerSecond,
            @DefaultValue("false") boolean trustForwardedHeader) {}
}
