package io.paynexa.notifications.consumer;

import io.paynexa.contracts.PaymentEvent;
import io.paynexa.notifications.persistence.NotificationStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final PaymentEventProcessor processor;
    private final NotificationStore store;

    PaymentEventListener(PaymentEventProcessor processor, NotificationStore store) {
        this.processor = processor;
        this.store = store;
    }

    @KafkaListener(topics = PaymentEvent.TOPIC, groupId = "notification-service")
    void onPaymentEvent(ConsumerRecord<String, String> record) {
        processor.process(record.value());
    }

    /**
     * Persists records that exhausted their retries. The payload is kept verbatim so an operator can inspect it and
     * replay it through the admin API once the cause is fixed.
     */
    @KafkaListener(topics = PaymentEvent.TOPIC + PaymentEvent.DEAD_LETTER_SUFFIX, groupId = "notification-service-dlt")
    void onDeadLetter(ConsumerRecord<String, String> record) {
        store.addDeadLetter(PaymentEvent.TOPIC, record.key(), record.value(), header(record, "kafka_dlt-exception-message"));
        log.error("event moved to dead-letter store (key={}); see /admin/dead-letters", record.key());
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
