package io.paynexa.payments.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class TransactionConfig {

    /**
     * Programmatic transactions on purpose: the boundary of every money-moving operation is visible in the code and
     * no remote call can accidentally end up inside one. READ COMMITTED is the PostgreSQL default; correctness
     * comes from explicit row locks and constraints, not from a stricter isolation level.
     */
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }
}
