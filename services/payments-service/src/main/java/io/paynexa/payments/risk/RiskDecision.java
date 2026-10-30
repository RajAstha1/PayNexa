package io.paynexa.payments.risk;

import java.util.List;

/** ALLOW < REVIEW < BLOCK. The overall decision is the most severe of the rule hits. */
public enum RiskDecision {
    ALLOW, REVIEW, BLOCK;

    public record Hit(String rule, RiskDecision decision, String detail) {}

    public record Outcome(RiskDecision decision, List<Hit> hits) {
        public static Outcome of(List<Hit> hits) {
            var worst = ALLOW;
            for (var h : hits) {
                if (h.decision().ordinal() > worst.ordinal()) {
                    worst = h.decision();
                }
            }
            return new Outcome(worst, List.copyOf(hits));
        }
    }
}
