package io.github.shubhajit93.campaign.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.api.DeliveryCompleted;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import io.github.shubhajit93.campaign.api.Queues;
import io.github.shubhajit93.campaign.service.domain.CampaignStatus;
import io.github.shubhajit93.campaign.service.outbox.OutboxRepository;
import io.github.shubhajit93.campaign.service.web.CampaignResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End to end against real PostgreSQL and SQS (LocalStack): create → launch → outbox relay → results → completion. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CampaignFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
            .withServices(LocalStackContainer.Service.SQS)
            .withCopyFileToContainer(MountableFile.forHostPath("../docker/localstack/init-queues.sh"), "/opt/init-queues.sh");

    @BeforeAll
    static void createQueues() throws Exception {
        ExecResult result = localstack.execInContainer("sh", "/opt/init-queues.sh");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    @DynamicPropertySource
    static void aws(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.sqs.endpoint", () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", localstack::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", localstack::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", localstack::getSecretKey);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    SqsAsyncClient sqs;

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

        // The relay sends every outbox row to the per-channel queues.
        List<DeliveryRequested> published = new ArrayList<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            published.addAll(drain(Queues.EMAIL_REQUESTED));
            published.addAll(drain(Queues.SMS_REQUESTED));
            return published.size() >= 3;
        });
        assertThat(published).filteredOn(d -> d.channel() == Channel.EMAIL).hasSize(2);
        assertThat(published).filteredOn(d -> d.channel() == Channel.SMS).hasSize(1);
        assertThat(published).allMatch(d -> d.campaignId().equals(created.id()));
        await().atMost(Duration.ofSeconds(10)).until(() -> outbox.countByPublishedAtIsNull() == 0);

        // Report results, including a duplicate that must not be counted twice.
        Instant now = Instant.now();
        sendResult(DeliveryCompleted.delivered(published.get(0), now));
        sendResult(DeliveryCompleted.delivered(published.get(0), now));
        sendResult(DeliveryCompleted.delivered(published.get(1), now));
        sendResult(DeliveryCompleted.failed(published.get(2), "Invalid address", now));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
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

    /** Receives and deletes whatever is currently in the queue. */
    private List<DeliveryRequested> drain(String queue) throws Exception {
        String url = sqs.getQueueUrl(r -> r.queueName(queue)).join().queueUrl();
        List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(1))
                .join().messages();
        List<DeliveryRequested> out = new ArrayList<>();
        for (Message m : messages) {
            out.add(json.readValue(m.body(), DeliveryRequested.class));
            sqs.deleteMessage(r -> r.queueUrl(url).receiptHandle(m.receiptHandle())).join();
        }
        return out;
    }

    private void sendResult(DeliveryCompleted result) throws Exception {
        String url = sqs.getQueueUrl(r -> r.queueName(Queues.DELIVERY_COMPLETED)).join().queueUrl();
        String payload = json.writeValueAsString(result);
        sqs.sendMessage(r -> r.queueUrl(url).messageBody(payload)).join();
    }

    private CampaignResponse fetch(UUID id) throws Exception {
        return read(mvc.perform(get("/api/campaigns/{id}", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private CampaignResponse read(String body) throws Exception {
        return json.readValue(body, CampaignResponse.class);
    }
}
