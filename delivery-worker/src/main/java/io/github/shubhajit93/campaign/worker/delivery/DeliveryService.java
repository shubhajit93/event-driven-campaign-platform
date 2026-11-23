package io.github.shubhajit93.campaign.worker.delivery;

import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.api.DeliveryCompleted;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import io.github.shubhajit93.campaign.worker.sender.ChannelSender;
import io.github.shubhajit93.campaign.worker.sender.PermanentDeliveryException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Processes one delivery request exactly once in effect.
 * <ul>
 *   <li>Duplicate (redelivered) messages are skipped through the processed-message table.</li>
 *   <li>Permanent failures are reported as FAILED straight away, without retrying.</li>
 *   <li>Transient failures propagate. The transaction rolls back (including the idempotency mark)
 *       and the Kafka error handler retries with backoff, then dead-letters the message.</li>
 * </ul>
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final Map<Channel, ChannelSender> senders = new EnumMap<>(Channel.class);
    private final ProcessedMessageStore processedMessages;
    private final ResultPublisher results;
    private final MeterRegistry metrics;
    private final Clock clock;

    public DeliveryService(List<ChannelSender> senders, ProcessedMessageStore processedMessages,
                           ResultPublisher results, MeterRegistry metrics, Clock clock) {
        senders.forEach(s -> this.senders.put(s.channel(), s));
        this.processedMessages = processedMessages;
        this.results = results;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public void process(DeliveryRequested request) {
        Instant now = clock.instant();
        if (!processedMessages.markProcessed(request.messageId(), now)) {
            log.debug("Skipping duplicate delivery {}", request.messageId());
            metrics.counter("campaign.deliveries.duplicates", "channel", request.channel().name()).increment();
            return;
        }
        ChannelSender sender = senders.get(request.channel());
        if (sender == null) {
            throw new IllegalStateException("No sender configured for channel " + request.channel());
        }
        DeliveryCompleted result;
        try {
            sender.send(request);
            result = DeliveryCompleted.delivered(request, now);
        } catch (PermanentDeliveryException e) {
            log.info("Delivery {} failed permanently: {}", request.messageId(), e.getMessage());
            result = DeliveryCompleted.failed(request, e.getMessage(), now);
        }
        results.publish(result);
        metrics.counter("campaign.deliveries", "channel", request.channel().name(),
                "status", result.status().name()).increment();
    }

    /** Called by the error handler once retries are exhausted, so the campaign still reaches a final state. */
    public void reportExhausted(DeliveryRequested request, String reason) {
        results.publish(DeliveryCompleted.failed(request, "Retries exhausted: " + reason, clock.instant()));
        metrics.counter("campaign.deliveries", "channel", request.channel().name(), "status", "EXHAUSTED").increment();
    }
}
