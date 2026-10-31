package io.paynexa.payments.risk;

import io.paynexa.payments.config.PaymentsProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RiskEngine {

    private final List<RiskRule> rules;
    private final PaymentsProperties props;

    @org.springframework.beans.factory.annotation.Autowired
    public RiskEngine(PaymentsProperties props) {
        this(props, Rules.defaults());
    }

    public RiskEngine(PaymentsProperties props, List<RiskRule> rules) {
        this.props = props;
        this.rules = List.copyOf(rules);
    }

    public RiskDecision.Outcome evaluate(RiskContextInput in, RiskStats stats) {
        var ctx = new RiskContext(in.kind(), in.payerAccountId(), in.payeeAccountId(), in.amount(), in.now(),
                props.risk().limitsFor(in.amount().currency()), stats);
        var hits = new ArrayList<RiskDecision.Hit>();
        for (var rule : rules) {
            rule.evaluate(ctx).ifPresent(hits::add);
        }
        return RiskDecision.Outcome.of(hits);
    }

    public record RiskContextInput(io.paynexa.payments.domain.PaymentKind kind, java.util.UUID payerAccountId,
                                   java.util.UUID payeeAccountId, io.paynexa.payments.domain.Money amount,
                                   java.time.Instant now) {}
}
