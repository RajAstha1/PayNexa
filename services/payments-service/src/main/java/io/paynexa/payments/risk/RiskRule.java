package io.paynexa.payments.risk;

import java.util.Optional;

public interface RiskRule {
    String name();

    Optional<RiskDecision.Hit> evaluate(RiskContext ctx);
}
