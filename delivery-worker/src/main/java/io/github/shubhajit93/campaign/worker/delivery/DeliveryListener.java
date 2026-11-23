package io.github.shubhajit93.campaign.worker.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.Visibility;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import io.github.shubhajit93.campaign.api.Queues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * One listener container per channel queue, each with its own concurrency, so a slow SMS provider never
 * holds up email delivery.
 * <p>
 * Retries use SQS itself rather than in-memory loops. On failure the message is not deleted, and its
 * visibility timeout is set to the backoff delay, so it reappears later on any worker instance. On the last
 * attempt a FAILED result is reported, and the redrive policy then moves the message to the DLQ for replay.
 */
@Component
public class DeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryListener.class);
    private static final String RECEIVE_COUNT = SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT;

    private final DeliveryService deliveryService;
    private final RetryPolicy retryPolicy;
    private final ObjectMapper objectMapper;

    public DeliveryListener(DeliveryService deliveryService, RetryPolicy retryPolicy, ObjectMapper objectMapper) {
        this.deliveryService = deliveryService;
        this.retryPolicy = retryPolicy;
        this.objectMapper = objectMapper;
    }

    @SqsListener(value = Queues.EMAIL_REQUESTED, id = "email-delivery",
            maxConcurrentMessages = "${delivery.email.concurrency:10}")
    public void onEmail(String payload, @Header(RECEIVE_COUNT) String receiveCount, Visibility visibility) {
        handle(payload, Integer.parseInt(receiveCount), visibility);
    }

    // A poll may not fetch more messages than the listener can process at once, so both follow the concurrency.
    @SqsListener(value = Queues.SMS_REQUESTED, id = "sms-delivery",
            maxConcurrentMessages = "${delivery.sms.concurrency:5}",
            maxMessagesPerPoll = "${delivery.sms.concurrency:5}")
    public void onSms(String payload, @Header(RECEIVE_COUNT) String receiveCount, Visibility visibility) {
        handle(payload, Integer.parseInt(receiveCount), visibility);
    }

    void handle(String payload, int attempt, Visibility visibility) {
        DeliveryRequested request;
        try {
            request = objectMapper.readValue(payload, DeliveryRequested.class);
        } catch (JsonProcessingException e) {
            // Retrying cannot fix a malformed payload, so let it reach the DLQ as fast as possible.
            visibility.changeTo(0);
            throw new IllegalArgumentException("Unreadable delivery message", e);
        }
        try {
            deliveryService.process(request);
        } catch (RuntimeException e) {
            if (retryPolicy.isLastAttempt(attempt)) {
                log.warn("Delivery {} failed on final attempt {}, sending to DLQ: {}",
                        request.messageId(), attempt, e.getMessage());
                deliveryService.reportExhausted(request, e.getMessage());
                visibility.changeTo(0);
            } else {
                int delay = retryPolicy.backoffSeconds(attempt);
                log.info("Delivery {} failed (attempt {}/{}), retrying in {}s: {}",
                        request.messageId(), attempt, retryPolicy.maxAttempts(), delay, e.getMessage());
                visibility.changeTo(delay);
            }
            throw e;
        }
    }
}
