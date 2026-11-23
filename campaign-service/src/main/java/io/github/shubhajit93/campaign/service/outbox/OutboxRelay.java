package io.github.shubhajit93.campaign.service.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls unpublished outbox rows and publishes them to Kafka. A row is marked published only after the broker
 * acknowledges it, so a crash at any point causes a re-send (at-least-once), never a lost event.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Properties properties;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, Properties properties) {
        this.repository = repository;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.interval-ms:500}")
    public void relay() {
        try {
            int published;
            do {
                published = publishBatch();
            } while (published == properties.batchSize());
        } catch (RuntimeException e) {
            // Rows stay unpublished and are picked up again on the next tick.
            log.warn("Outbox relay failed, will retry: {}", e.getMessage());
        }
    }

    int publishBatch() {
        Integer count = transactions.execute(status -> {
            List<OutboxEvent> batch = repository.lockNextBatch(properties.batchSize());
            if (batch.isEmpty()) {
                return 0;
            }
            CompletableFuture<?>[] sends = batch.stream()
                    .map(e -> kafka.send(e.getTopic(), e.getMessageKey(), e.getPayload()))
                    .toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.allOf(sends).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while publishing outbox batch", e);
            } catch (Exception e) {
                throw new IllegalStateException("Kafka did not acknowledge outbox batch", e);
            }
            Instant now = clock.instant();
            batch.forEach(e -> e.markPublished(now));
            return batch.size();
        });
        int published = count == null ? 0 : count;
        if (published > 0) {
            log.debug("Published {} outbox events", published);
        }
        return published;
    }

    @ConfigurationProperties("outbox.relay")
    public record Properties(int batchSize, Duration sendTimeout) {
        public Properties {
            if (batchSize <= 0) {
                batchSize = 200;
            }
            if (sendTimeout == null) {
                sendTimeout = Duration.ofSeconds(10);
            }
        }
    }
}
