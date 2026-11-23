package io.github.shubhajit93.campaign.service.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Polls unpublished outbox rows and sends them to SQS in batches of up to 10 (the SQS batch limit).
 * A row is marked published only after SQS accepts every message in the batch. A crash, or a partial batch
 * failure, causes a re-send (at-least-once) and never a lost event. Consumers deduplicate by message id.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int SQS_MAX_BATCH = 10;

    private final OutboxRepository repository;
    private final SqsAsyncClient sqs;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Properties properties;
    private final Map<String, String> queueUrls = new ConcurrentHashMap<>();

    public OutboxRelay(OutboxRepository repository, SqsAsyncClient sqs, TransactionTemplate transactions,
                       Clock clock, Properties properties) {
        this.repository = repository;
        this.sqs = sqs;
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
            List<CompletableFuture<SendMessageBatchResponse>> sends = new ArrayList<>();
            Map<String, List<OutboxEvent>> byQueue = batch.stream()
                    .collect(Collectors.groupingBy(OutboxEvent::getDestination, LinkedHashMap::new, Collectors.toList()));
            byQueue.forEach((queue, events) -> {
                String url = queueUrl(queue);
                for (int i = 0; i < events.size(); i += SQS_MAX_BATCH) {
                    List<SendMessageBatchRequestEntry> entries = events.subList(i, Math.min(i + SQS_MAX_BATCH, events.size()))
                            .stream()
                            .map(e -> SendMessageBatchRequestEntry.builder()
                                    .id(e.getId().toString())
                                    .messageBody(e.getPayload())
                                    .build())
                            .toList();
                    sends.add(sqs.sendMessageBatch(r -> r.queueUrl(url).entries(entries)));
                }
            });
            awaitAll(sends);
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

    private void awaitAll(List<CompletableFuture<SendMessageBatchResponse>> sends) {
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing outbox batch", e);
        } catch (Exception e) {
            throw new IllegalStateException("SQS did not accept outbox batch", e);
        }
        long failed = sends.stream().mapToLong(f -> f.join().failed().size()).sum();
        if (failed > 0) {
            // Roll back so the whole batch is retried. Entries that did succeed become harmless duplicates.
            throw new IllegalStateException(failed + " outbox message(s) rejected by SQS");
        }
    }

    private String queueUrl(String queueName) {
        return queueUrls.computeIfAbsent(queueName,
                name -> sqs.getQueueUrl(r -> r.queueName(name)).join().queueUrl());
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
