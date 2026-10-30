package io.paynexa.payments.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.paynexa.payments.rail.RailClient;
import io.paynexa.payments.rail.ResilientRailClient;
import io.paynexa.payments.rail.SimulatedRailClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RailConfig {

    /** The only rail implementation in this repository is the sandbox simulator. */
    @Bean
    SimulatedRailClient simulatedRail() {
        return new SimulatedRailClient();
    }

    @Bean
    @Primary
    RailClient railClient(SimulatedRailClient simulated, PaymentsProperties props, MeterRegistry meters) {
        var client = new ResilientRailClient(simulated, props.rail(), ResilientRailClient.defaultBreaker());
        // 0 = CLOSED, 1 = HALF_OPEN, 2 = OPEN (alert on 2)
        Gauge.builder("paynexa.rail.breaker.state", client, c -> switch (c.breaker().getState()) {
            case CLOSED, DISABLED, METRICS_ONLY -> 0;
            case HALF_OPEN -> 1;
            case OPEN, FORCED_OPEN -> 2;
        }).register(meters);
        return client;
    }
}
