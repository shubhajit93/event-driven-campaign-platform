package io.github.shubhajit93.campaign.worker.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shubhajit93.campaign.api.DeliveryCompleted;
import io.github.shubhajit93.campaign.api.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ResultPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public ResultPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    /**
     * Blocks until the broker acknowledges. If publishing fails, the surrounding transaction rolls back and the
     * request is retried. Downstream deduplicates by message id, so an occasional repeat is harmless.
     */
    public void publish(DeliveryCompleted result) {
        try {
            String payload = objectMapper.writeValueAsString(result);
            kafka.send(Topics.DELIVERY_COMPLETED, result.campaignId().toString(), payload).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing delivery result", e);
        } catch (JsonProcessingException | ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not publish delivery result " + result.messageId(), e);
        }
    }
}
