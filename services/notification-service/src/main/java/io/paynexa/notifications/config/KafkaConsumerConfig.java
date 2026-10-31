package io.paynexa.notifications.config;

import io.paynexa.contracts.PaymentEvent;
import io.paynexa.notifications.consumer.PaymentEventProcessor;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    org.apache.kafka.clients.admin.NewTopic paymentsTopic() {
        return TopicBuilder.name(PaymentEvent.TOPIC).partitions(6).replicas(1).build();
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic paymentsDeadLetterTopic() {
        return TopicBuilder.name(PaymentEvent.TOPIC + PaymentEvent.DEAD_LETTER_SUFFIX).partitions(1).replicas(1).build();
    }

    /**
     * Bounded retries with exponential backoff, then dead-letter. Payloads that can never succeed skip the retries.
     * The listener container commits an offset only after the record was handled or dead-lettered.
     */
    @Bean
    CommonErrorHandler errorHandler(KafkaOperations<Object, Object> template,
                                    @Value("${paynexa.notifications.retry.max-attempts:4}") int maxAttempts,
                                    @Value("${paynexa.notifications.retry.initial-interval:1s}") Duration initial,
                                    @Value("${paynexa.notifications.retry.multiplier:2.0}") double multiplier,
                                    @Value("${paynexa.notifications.retry.max-interval:10s}") Duration max) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + PaymentEvent.DEAD_LETTER_SUFFIX, -1));
        var backoff = new ExponentialBackOff(initial.toMillis(), multiplier);
        backoff.setMaxInterval(max.toMillis());
        backoff.setMaxAttempts(Math.max(0, maxAttempts - 1));   // maxAttempts counts the first delivery
        var handler = new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(PaymentEventProcessor.UnprocessableEventException.class);
        return handler;
    }
}
