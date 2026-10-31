package io.paynexa.notifications.api;

import io.paynexa.contracts.PaymentEvent;
import io.paynexa.notifications.persistence.NotificationStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Operator tooling for events that exhausted their retries. Admin only. */
@RestController
@RequestMapping("/admin/dead-letters")
class DeadLetterController {

    private final NotificationStore store;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;

    DeadLetterController(NotificationStore store, KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
        this.store = store;
        this.kafka = kafka;
        this.tx = tx;
    }

    @GetMapping
    List<NotificationStore.DeadLetter> list() {
        return store.deadLetters("NEW", 100);
    }

    /**
     * Re-publishes the stored payload to the main topic. Safe to repeat: the processor deduplicates by event id, so a
     * replay of an event that was in fact applied is a no-op.
     */
    @PostMapping("/{id}/replay")
    ResponseEntity<Map<String, Object>> replay(@PathVariable long id) throws Exception {
        var letter = tx.execute(s -> store.deadLetterForUpdate(id).orElse(null));
        if (letter == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "DEAD_LETTER_NOT_FOUND"));
        }
        if (!"NEW".equals(letter.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "ALREADY_REPLAYED"));
        }
        kafka.send(PaymentEvent.TOPIC, letter.key(), letter.payload()).get(5, TimeUnit.SECONDS);   // acknowledged before marking
        store.markReplayed(id);
        return ResponseEntity.ok(Map.of("replayed", id));
    }
}
