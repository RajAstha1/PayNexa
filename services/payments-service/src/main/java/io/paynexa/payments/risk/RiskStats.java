package io.paynexa.payments.risk;

import java.time.Instant;
import java.util.UUID;

/** Read-side queries the rules need. Called only while the payer account row is locked, so the numbers are race-free for that payer. */
public interface RiskStats {
    /** Sum of money-moving payments out of the account since {@code since}, in minor units. */
    long outflowSince(UUID payerAccountId, Instant since);

    long paymentCountSince(UUID payerAccountId, Instant since);

    boolean hasPaidBefore(UUID payerAccountId, UUID payeeAccountId);
}
