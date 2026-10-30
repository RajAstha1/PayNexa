package io.paynexa.payments.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final Duration timeout;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka,
                               @Value("${paynexa.payments.outbox.send-timeout:5s}") Duration timeout) {
        this.kafka = kafka;
        this.timeout = timeout;
    }

    @Override
    public void publish(String topic, String key, String payload, Map<String, String> headers) throws Exception {
        var record = new ProducerRecord<>(topic, key, payload);
        headers.forEach((k, v) -> record.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8))));
        // Block for the broker acknowledgement (acks=all): only an acknowledged event may be marked PUBLISHED.
        kafka.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
