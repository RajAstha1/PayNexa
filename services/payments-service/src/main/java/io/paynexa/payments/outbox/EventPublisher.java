package io.paynexa.payments.outbox;

import java.util.Map;

/** Port to the message broker. Implementations must block until the broker acknowledges, or throw. */
public interface EventPublisher {

    void publish(String topic, String key, String payload, Map<String, String> headers) throws Exception;
}
