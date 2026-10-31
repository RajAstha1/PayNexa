package io.paynexa.payments.outbox;

import io.paynexa.contracts.PaymentEvent;
import io.paynexa.payments.domain.Money;
import io.paynexa.payments.service.PaymentService.TransferCommand;
import io.paynexa.payments.support.TestKit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The real KafkaEventPublisher and OutboxRelay against a real in-JVM broker, read back with a plain consumer. */
class KafkaPublishIntegrationTest {

    static EmbeddedKafkaBroker broker;
    static TestKit kit;

    @BeforeAll
    static void start() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, PaymentEvent.TOPIC);
        broker.afterPropertiesSet();
        kit = TestKit.create();
    }

    @AfterAll
    static void stop() {
        kit.close();
        broker.destroy();
    }

    private KafkaEventPublisher publisher() {
        var props = new java.util.HashMap<String, Object>(KafkaTestUtils.producerProps(broker));
        props.put("acks", "all");
        props.put("enable.idempotence", true);
        var template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer()));
        return new KafkaEventPublisher(template, Duration.ofSeconds(10));
    }

    @Test
    void committedPaymentEventsReachKafkaWithKeyAndHeadersAndOnlyOnce() {
        var alice = kit.newUser();
        var a = kit.fundedAccount(alice, "USD", "100.00");
        var b = kit.openAccount(kit.newUser(), "USD");
        var payment = kit.paymentService.transfer(alice, "kafka-" + UUID.randomUUID(),
                new TransferCommand(a.id(), b.id(), Money.parse("9.99", "USD"), null)).payment();
        var relay = new OutboxRelay(kit.tx, kit.outbox, publisher(), kit.props, kit.clock, kit.meters);

        assertThat(relay.runOnce()).isGreaterThanOrEqualTo(2);   // funding event + transfer event
        assertThat(relay.runOnce()).isZero();

        var consumerProps = KafkaTestUtils.consumerProps(broker, "verify-" + UUID.randomUUID(), true);
        consumerProps.put("auto.offset.reset", "earliest");
        try (var consumer = new DefaultKafkaConsumerFactory<>(consumerProps, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, PaymentEvent.TOPIC);
            var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(15));
            List<ConsumerRecord<String, String>> mine = new java.util.ArrayList<>();
            records.forEach(r -> { if (payment.id().toString().equals(r.key())) mine.add(r); });

            assertThat(mine).hasSize(1);
            var record = mine.get(0);
            var event = JsonMapper.builder().build().readValue(record.value(), PaymentEvent.class);
            assertThat(event.type()).isEqualTo(PaymentEvent.COMPLETED);
            assertThat(event.paymentId()).isEqualTo(payment.id());
            assertThat(event.amountMinor()).isEqualTo(999);
            assertThat(event.sequence()).isEqualTo(1);
            assertThat(header(record, "event-id")).isEqualTo(event.eventId().toString());
            assertThat(header(record, "event-type")).isEqualTo(PaymentEvent.COMPLETED);
            assertThat(record.value()).doesNotContain("destination").doesNotContain("reference");
        }
    }

    private static String header(ConsumerRecord<?, ?> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
