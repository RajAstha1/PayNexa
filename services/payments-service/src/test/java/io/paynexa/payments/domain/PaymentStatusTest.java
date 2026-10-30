package io.paynexa.payments.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Enumerates every (kind, from, to) combination so a new status or edge cannot be added without a decision here. */
class PaymentStatusTest {

    private static final Set<String> LEGAL = Set.of(
            "TRANSFER:PENDING_REVIEW>COMPLETED", "TRANSFER:PENDING_REVIEW>REJECTED", "TRANSFER:PENDING_REVIEW>FAILED",
            "TRANSFER:COMPLETED>REVERSED",
            "PAYOUT:PENDING_REVIEW>SUBMITTED", "PAYOUT:PENDING_REVIEW>REJECTED", "PAYOUT:PENDING_REVIEW>FAILED",
            "PAYOUT:SUBMITTED>SETTLED", "PAYOUT:SUBMITTED>REVERSED",
            "FUNDING:PENDING_REVIEW>COMPLETED", "FUNDING:PENDING_REVIEW>REJECTED", "FUNDING:PENDING_REVIEW>FAILED");

    @Test
    void exactlyTheDocumentedTransitionsAreLegal() {
        for (var kind : PaymentKind.values()) {
            for (var from : PaymentStatus.values()) {
                for (var to : PaymentStatus.values()) {
                    boolean expected = LEGAL.contains(kind + ":" + from + ">" + to);
                    assertThat(from.canTransitionTo(kind, to))
                            .as("%s %s -> %s", kind, from, to).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void terminalStatesHaveNoExits() {
        for (var kind : PaymentKind.values()) {
            for (var s : EnumSet.of(PaymentStatus.FAILED, PaymentStatus.REJECTED, PaymentStatus.REVERSED, PaymentStatus.SETTLED)) {
                assertThat(s.isTerminal(kind)).isTrue();
            }
        }
    }

    @Test
    void settledPayoutCannotBeReversedAndSelfTransitionsAreIllegal() {
        assertThat(PaymentStatus.SETTLED.canTransitionTo(PaymentKind.PAYOUT, PaymentStatus.REVERSED)).isFalse();
        for (var s : PaymentStatus.values()) {
            for (var kind : PaymentKind.values()) {
                assertThat(s.canTransitionTo(kind, s)).isFalse();
            }
        }
    }

    @Test
    void onlyMoneyMovingStatusesCountTowardsLimits() {
        assertThat(PaymentStatus.COMPLETED.movedMoney()).isTrue();
        assertThat(PaymentStatus.SUBMITTED.movedMoney()).isTrue();
        assertThat(PaymentStatus.SETTLED.movedMoney()).isTrue();
        assertThat(PaymentStatus.FAILED.movedMoney()).isFalse();
        assertThat(PaymentStatus.REJECTED.movedMoney()).isFalse();
        assertThat(PaymentStatus.PENDING_REVIEW.movedMoney()).isFalse();
    }
}
