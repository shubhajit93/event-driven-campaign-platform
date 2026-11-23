package io.github.shubhajit93.campaign.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.api.DeliveryCompleted;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import io.github.shubhajit93.campaign.api.Topics;
import io.github.shubhajit93.campaign.service.domain.CampaignStatus;
import io.github.shubhajit93.campaign.service.outbox.OutboxRepository;
import io.github.shubhajit93.campaign.service.web.CampaignResponse;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End to end against real PostgreSQL and Kafka: create → launch → outbox relay → results → completion. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CampaignFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    OutboxRepository outbox;

    @Test
    void campaignIsPublishedThroughOutboxAndCompletesFromResults() throws Exception {
        String body = """
                {
                  "name": "Product launch",
                  "template": "Hi {{name}}, it's live!",
                  "channels": ["EMAIL", "SMS"],
                  "recipients": [
                    {"name": "Alice", "email": "alice@example.com", "phone": "+8801700000000"},
                    {"name": "Bob", "email": "bob@example.com"}
                  ]
                }
                """;
        CampaignResponse created = read(mvc.perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        CampaignResponse launched = read(mvc.perform(post("/api/campaigns/{id}/launch", created.id()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertThat(launched.expectedDeliveries()).isEqualTo(3);

        // The relay publishes every outbox row to the per-channel topics.
        List<DeliveryRequested> published = consume(3);
        assertThat(published).filteredOn(d -> d.channel() == Channel.EMAIL).hasSize(2);
        assertThat(published).filteredOn(d -> d.channel() == Channel.SMS).hasSize(1);
        assertThat(published).allMatch(d -> d.campaignId().equals(created.id()));
        await().atMost(Duration.ofSeconds(10)).until(() -> outbox.countByPublishedAtIsNull() == 0);

        // Report results, including a duplicate that must not be counted twice.
        Instant now = Instant.now();
        send(DeliveryCompleted.delivered(published.get(0), now));
        send(DeliveryCompleted.delivered(published.get(0), now));
        send(DeliveryCompleted.delivered(published.get(1), now));
        send(DeliveryCompleted.failed(published.get(2), "Invalid address", now));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            CampaignResponse current = fetch(created.id());
            assertThat(current.status()).isEqualTo(CampaignStatus.COMPLETED);
            assertThat(current.delivered()).isEqualTo(2);
            assertThat(current.failed()).isEqualTo(1);
        });

        mvc.perform(post("/api/campaigns/{id}/launch", created.id())).andExpect(status().isConflict());
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        mvc.perform(post("/api/campaigns").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"template\":\"x\",\"channels\":[],\"recipients\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/campaigns/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    private List<DeliveryRequested> consume(int expected) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<DeliveryRequested> received = new ArrayList<>();
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(Topics.EMAIL_REQUESTED, Topics.SMS_REQUESTED));
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    received.add(json.readValue(record.value(), DeliveryRequested.class));
                }
                return received.size() >= expected;
            });
        }
        return received;
    }

    private void send(DeliveryCompleted result) throws Exception {
        kafkaTemplate.send(Topics.DELIVERY_COMPLETED, result.campaignId().toString(), json.writeValueAsString(result))
                .get();
    }

    private CampaignResponse fetch(UUID id) throws Exception {
        return read(mvc.perform(get("/api/campaigns/{id}", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private CampaignResponse read(String body) throws Exception {
        return json.readValue(body, CampaignResponse.class);
    }
}
