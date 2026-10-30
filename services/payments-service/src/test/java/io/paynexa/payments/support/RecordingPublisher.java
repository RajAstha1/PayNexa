package io.paynexa.payments.support;

import io.paynexa.payments.outbox.EventPublisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** Test double for the broker with controllable failures. */
public class RecordingPublisher implements EventPublisher {

    public record Sent(String topic, String key, String payload, Map<String, String> headers) {}

    public final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    public final AtomicInteger attempts = new AtomicInteger();
    public volatile Predicate<Sent> failWhen = s -> false;
    /** Simulates "broker acknowledged but the relay died before committing" by throwing after recording. */
    public volatile boolean throwAfterRecording;

    @Override
    public void publish(String topic, String key, String payload, Map<String, String> headers) throws Exception {
        attempts.incrementAndGet();
        var s = new Sent(topic, key, payload, headers);
        if (failWhen.test(s)) {
            throw new java.io.IOException("broker unavailable");
        }
        sent.add(s);
        if (throwAfterRecording) {
            throw new java.io.IOException("ack lost");
        }
    }
}
