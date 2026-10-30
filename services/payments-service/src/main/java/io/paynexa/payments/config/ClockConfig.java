package io.paynexa.payments.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(PaymentsProperties.class)
public class ClockConfig {

    /** Injected everywhere time matters, so tests can control it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
