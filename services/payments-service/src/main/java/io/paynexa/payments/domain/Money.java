package io.paynexa.payments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Set;

/**
 * An amount in the minor unit of a currency (cents for USD, yen for JPY). Exact integer arithmetic only.
 * Construction rejects unsupported currencies, non-positive amounts and values above {@link #MAX_MINOR}.
 */
public record Money(long minor, String currency) {

    /** Currencies the sandbox supports. Adding one is a deliberate, reviewed change. */
    public static final Set<String> SUPPORTED = Set.of("USD", "EUR", "GBP", "JPY", "INR", "CAD", "AUD", "CHF");

    /** Same bound as the database CHECK constraint (10^12 minor units). */
    public static final long MAX_MINOR = 1_000_000_000_000L;

    public Money {
        if (!SUPPORTED.contains(currency)) {
            throw new InvalidMoneyException("UNSUPPORTED_CURRENCY", "Unsupported currency");
        }
        if (minor <= 0) {
            throw new InvalidMoneyException("INVALID_AMOUNT", "Amount must be greater than zero");
        }
        if (minor > MAX_MINOR) {
            throw new InvalidMoneyException("AMOUNT_TOO_LARGE", "Amount exceeds the maximum allowed");
        }
    }

    /** Parses a plain decimal string ("12.34"). Rejects exponents, signs other than none, and excess precision. */
    public static Money parse(String decimal, String currency) {
        if (decimal == null || !decimal.matches("\\d{1,15}(\\.\\d{1,6})?")) {
            throw new InvalidMoneyException("INVALID_AMOUNT", "Amount must be a plain positive decimal such as 12.34");
        }
        if (currency == null || !SUPPORTED.contains(currency)) {
            throw new InvalidMoneyException("UNSUPPORTED_CURRENCY", "Unsupported currency");
        }
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        var value = new BigDecimal(decimal);
        BigDecimal scaled;
        try {
            scaled = value.setScale(digits, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvalidMoneyException("INVALID_PRECISION",
                    "Currency %s allows at most %d decimal places".formatted(currency, digits));
        }
        try {
            return new Money(scaled.movePointRight(digits).longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new InvalidMoneyException("AMOUNT_TOO_LARGE", "Amount exceeds the maximum allowed");
        }
    }

    public String toDecimalString() {
        return format(minor, currency);
    }

    public static String format(long minor, String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return BigDecimal.valueOf(minor, digits).toPlainString();
    }
}
