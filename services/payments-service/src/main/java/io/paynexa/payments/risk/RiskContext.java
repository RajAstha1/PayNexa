package io.paynexa.payments.risk;

import io.paynexa.payments.config.PaymentsProperties;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.domain.PaymentKind;

import java.time.Instant;
import java.util.UUID;

public record RiskContext(
        PaymentKind kind,
        UUID payerAccountId,
        UUID payeeAccountId,
        Money amount,
        Instant now,
        PaymentsProperties.Limits limits,
        RiskStats stats) {}
