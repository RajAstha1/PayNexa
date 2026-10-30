package io.paynexa.payments.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void parsesExactlyWithoutFloatingPoint() {
        assertThat(Money.parse("12.34", "USD").minor()).isEqualTo(1234);
        assertThat(Money.parse("12.3", "USD").minor()).isEqualTo(1230);
        assertThat(Money.parse("12", "USD").minor()).isEqualTo(1200);
        assertThat(Money.parse("0.10", "USD").minor()).isEqualTo(10);
        assertThat(Money.parse("500", "JPY").minor()).isEqualTo(500);
        // 0.1 + 0.2 style traps: exact in minor units
        assertThat(Money.parse("0.10", "USD").minor() + Money.parse("0.20", "USD").minor()).isEqualTo(30);
    }

    @Test
    void rejectsMorePrecisionThanTheCurrencyAllows() {
        assertThatThrownBy(() -> Money.parse("12.345", "USD")).hasMessageContaining("at most 2");
        assertThatThrownBy(() -> Money.parse("12.5", "JPY")).hasMessageContaining("at most 0");
        // trailing zeros beyond precision are still exact and accepted
        assertThat(Money.parse("12.340", "USD").minor()).isEqualTo(1234);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-1", "+1", "1e3", "1E3", "NaN", "Infinity", "1,000.00", "0x10", "1.", ".5", "abc", "1 0"})
    void rejectsMalformedAmounts(String bad) {
        assertThatThrownBy(() -> Money.parse(bad, "USD")).isInstanceOf(InvalidMoneyException.class);
    }

    @Test
    void rejectsZeroNegativeAndNull() {
        assertThatThrownBy(() -> Money.parse("0", "USD")).hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> Money.parse("0.00", "USD")).isInstanceOf(InvalidMoneyException.class);
        assertThatThrownBy(() -> Money.parse(null, "USD")).isInstanceOf(InvalidMoneyException.class);
        assertThatThrownBy(() -> new Money(-5, "USD")).isInstanceOf(InvalidMoneyException.class);
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertThatThrownBy(() -> Money.parse("1", "XXX")).hasMessageContaining("Unsupported currency");
        assertThatThrownBy(() -> Money.parse("1", "usd")).isInstanceOf(InvalidMoneyException.class);
        assertThatThrownBy(() -> Money.parse("1", null)).isInstanceOf(InvalidMoneyException.class);
    }

    @Test
    void enforcesMaximum() {
        assertThat(Money.parse("10000000000.00", "USD").minor()).isEqualTo(Money.MAX_MINOR);
        assertThatThrownBy(() -> Money.parse("10000000000.01", "USD")).hasMessageContaining("maximum");
        assertThatThrownBy(() -> Money.parse("999999999999999", "USD")).isInstanceOf(InvalidMoneyException.class);
    }

    @Test
    void formatsBackToExactDecimal() {
        assertThat(Money.parse("12.30", "USD").toDecimalString()).isEqualTo("12.30");
        assertThat(Money.parse("7", "JPY").toDecimalString()).isEqualTo("7");
        assertThat(Money.format(5, "USD")).isEqualTo("0.05");
    }
}
