package io.github.shubhajit93.campaign.worker.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shubhajit93.campaign.api.DeliveryCompleted;
import io.github.shubhajit93.campaign.api.Queues;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ResultPublisher {

    private final SqsAsyncClient sqs;
    private final ObjectMapper objectMapper;
    private volatile String queueUrl;

    public ResultPublisher(SqsAsyncClient sqs, ObjectMapper objectMapper) {
        this.sqs = sqs;
        this.objectMapper = objectMapper;
    }

    /**
     * Blocks until SQS accepts the message. If sending fails, the surrounding transaction rolls back and the
     * request is retried. Downstream deduplicates by message id, so an occasional repeat is harmless.
     */
    public void publish(DeliveryCompleted result) {
        try {
            String payload = objectMapper.writeValueAsString(result);
            sqs.sendMessage(r -> r.queueUrl(queueUrl()).messageBody(payload)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing delivery result", e);
        } catch (JsonProcessingException | ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not publish delivery result " + result.messageId(), e);
        }
    }

    private String queueUrl() {
        if (queueUrl == null) {
            queueUrl = sqs.getQueueUrl(r -> r.queueName(Queues.DELIVERY_COMPLETED)).join().queueUrl();
        }
        return queueUrl;
    }
}
