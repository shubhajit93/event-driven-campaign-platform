package io.github.shubhajit93.campaign.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.api.DeliveryCompleted;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import io.github.shubhajit93.campaign.api.DeliveryStatus;
import io.github.shubhajit93.campaign.api.Topics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DeliveryWorkerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper json;

    @Test
    void redeliveredRequestsProduceExactlyOneResultEach() throws Exception {
        UUID campaignId = UUID.randomUUID();
        var email = new DeliveryRequested(UUID.randomUUID(), campaignId, UUID.randomUUID(),
                Channel.EMAIL, "alice@example.com", "Hi", Instant.now());
        var badSms = new DeliveryRequested(UUID.randomUUID(), campaignId, UUID.randomUUID(),
                Channel.SMS, "12345", "Hi", Instant.now());

        send(Topics.EMAIL_REQUESTED, email);
        send(Topics.EMAIL_REQUESTED, email); // simulated redelivery
        send(Topics.SMS_REQUESTED, badSms);

        List<DeliveryCompleted> results = new ArrayList<>();
        try (Consumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(Topics.DELIVERY_COMPLETED));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                poll(consumer, results);
                return results.size() >= 2;
            });
            // Give a duplicate the chance to show up before asserting there is none.
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                poll(consumer, results);
            }
        }

        assertThat(results).hasSize(2);
        assertThat(results).filteredOn(r -> r.messageId().equals(email.messageId()))
                .singleElement().extracting(DeliveryCompleted::status).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(results).filteredOn(r -> r.messageId().equals(badSms.messageId()))
                .singleElement().extracting(DeliveryCompleted::status).isEqualTo(DeliveryStatus.FAILED);
    }

    private void send(String topic, DeliveryRequested request) throws Exception {
        kafkaTemplate.send(topic, request.recipientId().toString(), json.writeValueAsString(request)).get();
    }

    private void poll(Consumer<String, String> consumer, List<DeliveryCompleted> into) throws Exception {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
            into.add(json.readValue(record.value(), DeliveryCompleted.class));
        }
    }

    private Consumer<String, String> consumer() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }
}
