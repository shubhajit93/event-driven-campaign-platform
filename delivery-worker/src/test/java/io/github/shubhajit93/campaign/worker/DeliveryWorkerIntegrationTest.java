package io.github.shubhajit93.campaign.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.api.DeliveryCompleted;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import io.github.shubhajit93.campaign.api.DeliveryStatus;
import io.github.shubhajit93.campaign.api.Queues;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the worker against real PostgreSQL and SQS (LocalStack) with the same redrive setup as production,
 * using 2 attempts and a 1s backoff so the retry and dead-letter path finishes quickly.
 */
@SpringBootTest(properties = {"delivery.retry.max-attempts=2", "delivery.retry.initial-backoff=1s"})
@Testcontainers(disabledWithoutDocker = true)
class DeliveryWorkerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
            .withServices(LocalStackContainer.Service.SQS)
            .withCopyFileToContainer(MountableFile.forHostPath("../docker/localstack/init-queues.sh"), "/opt/init-queues.sh");

    @BeforeAll
    static void createQueues() throws Exception {
        ExecResult result = localstack.execInContainer("sh", "-c", "MAX_RECEIVE_COUNT=2 sh /opt/init-queues.sh");
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
    SqsAsyncClient sqs;

    @Autowired
    ObjectMapper json;

    /** Every result seen on the results queue, shared across tests. Each test filters by its own message ids. */
    private static final List<DeliveryCompleted> results = new CopyOnWriteArrayList<>();

    @Test
    void redeliveredRequestsProduceExactlyOneResultEach() throws Exception {
        UUID campaignId = UUID.randomUUID();
        var email = request(campaignId, Channel.EMAIL, "alice@example.com");
        var badSms = request(campaignId, Channel.SMS, "12345");

        send(Queues.EMAIL_REQUESTED, email);
        send(Queues.EMAIL_REQUESTED, email); // simulated duplicate delivery
        send(Queues.SMS_REQUESTED, badSms);

        Set<UUID> ids = Set.of(email.messageId(), badSms.messageId());
        await().atMost(Duration.ofSeconds(30)).until(() -> resultsFor(ids).size() >= 2);
        // Give a duplicate the chance to show up before asserting there is none.
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            collectResults();
        }

        assertThat(resultsFor(ids)).hasSize(2);
        assertThat(resultsFor(Set.of(email.messageId()))).singleElement()
                .extracting(DeliveryCompleted::status).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(resultsFor(Set.of(badSms.messageId()))).singleElement()
                .extracting(DeliveryCompleted::status).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void transientFailuresAreRetriedThenReportedAndDeadLettered() throws Exception {
        // Addresses containing "flaky" always fail transiently in the simulated provider.
        var flaky = request(UUID.randomUUID(), Channel.EMAIL, "someone@flaky.example");
        send(Queues.EMAIL_REQUESTED, flaky);

        await().atMost(Duration.ofSeconds(45)).until(() -> !resultsFor(Set.of(flaky.messageId())).isEmpty());
        assertThat(resultsFor(Set.of(flaky.messageId()))).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(r.failureReason()).startsWith("Retries exhausted");
        });

        // After the final attempt, the SQS redrive policy moves the original message to the DLQ for replay.
        String dlq = url(Queues.EMAIL_REQUESTED + Queues.DLQ_SUFFIX);
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            List<Message> dead = sqs.receiveMessage(r -> r.queueUrl(dlq).maxNumberOfMessages(10).waitTimeSeconds(1))
                    .join().messages();
            assertThat(dead).anySatisfy(m -> assertThat(m.body()).contains(flaky.messageId().toString()));
        });
    }

    private static DeliveryRequested request(UUID campaignId, Channel channel, String address) {
        return new DeliveryRequested(UUID.randomUUID(), campaignId, UUID.randomUUID(), channel, address, "Hi",
                Instant.now());
    }

    private void send(String queue, DeliveryRequested request) throws Exception {
        String payload = json.writeValueAsString(request);
        String queueUrl = url(queue);
        sqs.sendMessage(r -> r.queueUrl(queueUrl).messageBody(payload)).join();
    }

    private List<DeliveryCompleted> resultsFor(Set<UUID> messageIds) throws Exception {
        collectResults();
        return results.stream().filter(r -> messageIds.contains(r.messageId())).toList();
    }

    private void collectResults() throws Exception {
        String queueUrl = url(Queues.DELIVERY_COMPLETED);
        for (Message m : sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1))
                .join().messages()) {
            results.add(json.readValue(m.body(), DeliveryCompleted.class));
            sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(m.receiptHandle())).join();
        }
    }

    private String url(String queue) {
        return sqs.getQueueUrl(r -> r.queueName(queue)).join().queueUrl();
    }
}
