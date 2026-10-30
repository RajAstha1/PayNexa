package io.paynexa.payments.risk;

import io.paynexa.payments.domain.Money;
import io.paynexa.payments.domain.PaymentKind;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

/** The built-in rule set. Thresholds come from configuration; nothing is hard-coded. */
public final class Rules {

    private Rules() {}

    public static List<RiskRule> defaults() {
        return List.of(new MaxTransaction(), new DailyOutflow(), new Velocity(), new ReviewThreshold(), new FirstPayeeHighAmount());
    }

    static long toMinor(BigDecimal major, String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return major.movePointRight(digits).longValue();
    }

    static String fmt(long minor, String currency) {
        return Money.format(minor, currency) + " " + currency;
    }

    public static final class MaxTransaction implements RiskRule {
        public String name() { return "MAX_TRANSACTION"; }

        public Optional<RiskDecision.Hit> evaluate(RiskContext c) {
            long max = toMinor(c.limits().maxTransaction(), c.amount().currency());
            return c.amount().minor() > max
                    ? Optional.of(new RiskDecision.Hit(name(), RiskDecision.BLOCK, "amount exceeds single-payment limit of " + fmt(max, c.amount().currency())))
                    : Optional.empty();
        }
    }

    public static final class DailyOutflow implements RiskRule {
        public String name() { return "DAILY_OUTFLOW"; }

        public Optional<RiskDecision.Hit> evaluate(RiskContext c) {
            long limit = toMinor(c.limits().dailyOutflow(), c.amount().currency());
            long already = c.stats().outflowSince(c.payerAccountId(), c.now().minusSeconds(24 * 3600));
            return already + c.amount().minor() > limit
                    ? Optional.of(new RiskDecision.Hit(name(), RiskDecision.BLOCK, "rolling 24h outflow would exceed " + fmt(limit, c.amount().currency())))
                    : Optional.empty();
        }
    }

    public static final class Velocity implements RiskRule {
        public String name() { return "VELOCITY"; }

        public Optional<RiskDecision.Hit> evaluate(RiskContext c) {
            long count = c.stats().paymentCountSince(c.payerAccountId(), c.now().minus(c.limits().velocityWindow()));
            return count >= c.limits().velocityMaxPayments()
                    ? Optional.of(new RiskDecision.Hit(name(), RiskDecision.REVIEW,
                            count + " payments in " + c.limits().velocityWindow() + " (limit " + c.limits().velocityMaxPayments() + ")"))
                    : Optional.empty();
        }
    }

    public static final class ReviewThreshold implements RiskRule {
        public String name() { return "REVIEW_THRESHOLD"; }

        public Optional<RiskDecision.Hit> evaluate(RiskContext c) {
            long threshold = toMinor(c.limits().reviewThreshold(), c.amount().currency());
            return c.amount().minor() >= threshold
                    ? Optional.of(new RiskDecision.Hit(name(), RiskDecision.REVIEW, "amount at or above review threshold of " + fmt(threshold, c.amount().currency())))
                    : Optional.empty();
        }
    }

    public static final class FirstPayeeHighAmount implements RiskRule {
        public String name() { return "FIRST_PAYEE_HIGH_AMOUNT"; }

        public Optional<RiskDecision.Hit> evaluate(RiskContext c) {
            if (c.kind() != PaymentKind.TRANSFER || c.payeeAccountId() == null) {
                return Optional.empty();
            }
            long threshold = toMinor(c.limits().firstPayeeReviewThreshold(), c.amount().currency());
            if (c.amount().minor() >= threshold && !c.stats().hasPaidBefore(c.payerAccountId(), c.payeeAccountId())) {
                return Optional.of(new RiskDecision.Hit(name(), RiskDecision.REVIEW,
                        "first payment to this payee at or above " + fmt(threshold, c.amount().currency())));
            }
            return Optional.empty();
        }
    }
}
