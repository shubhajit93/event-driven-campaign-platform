package io.github.shubhajit93.campaign.worker.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import io.github.shubhajit93.campaign.api.Topics;
import io.github.shubhajit93.campaign.worker.delivery.DeliveryService;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.time.Clock;
import java.time.Duration;
import java.util.stream.Stream;

@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Retries transient failures with exponential backoff. When retries run out, the record goes to
     * {@code <topic>.DLT} for inspection or replay, and a FAILED result is reported so the campaign can still
     * complete. Malformed payloads are not retried, because they would fail the same way every time.
     * Spring Boot applies this handler to every listener container automatically.
     */
    @Bean
    CommonErrorHandler deliveryErrorHandler(KafkaOperations<String, String> kafka, DeliveryService deliveryService,
                                            ObjectMapper objectMapper, RetryProperties retry) {
        var deadLetter = new DeadLetterPublishingRecoverer(kafka);
        var backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());

        var handler = new DefaultErrorHandler((ConsumerRecord<?, ?> record, Exception failure) -> {
            deadLetter.accept(record, failure);
            reportFailure(record, failure, deliveryService, objectMapper);
        }, backOff);
        handler.addNotRetryableExceptions(JsonProcessingException.class);
        return handler;
    }

    private static void reportFailure(ConsumerRecord<?, ?> record, Exception failure,
                                      DeliveryService deliveryService, ObjectMapper objectMapper) {
        try {
            DeliveryRequested request = objectMapper.readValue(String.valueOf(record.value()), DeliveryRequested.class);
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            deliveryService.reportExhausted(request, cause.getMessage());
        } catch (JsonProcessingException e) {
            log.error("Dead-lettered unreadable record from {} at offset {}", record.topic(), record.offset());
        }
    }

    /**
     * Every topic this worker reads or writes. Declaring them is idempotent. The recoverer writes to the same
     * partition number it read from, so each DLT needs at least as many partitions as its source topic.
     */
    @Bean
    KafkaAdmin.NewTopics workerTopics(@Value("${app.kafka.partitions:3}") int partitions,
                                      @Value("${app.kafka.replicas:1}") int replicas) {
        return new KafkaAdmin.NewTopics(Stream.of(
                        Topics.EMAIL_REQUESTED, Topics.SMS_REQUESTED, Topics.DELIVERY_COMPLETED,
                        Topics.EMAIL_REQUESTED + Topics.DLT_SUFFIX, Topics.SMS_REQUESTED + Topics.DLT_SUFFIX)
                .map(name -> TopicBuilder.name(name).partitions(partitions).replicas(replicas).build())
                .toArray(NewTopic[]::new));
    }

    @ConfigurationProperties("delivery.retry")
    public record RetryProperties(int maxRetries, Duration initialInterval, double multiplier, Duration maxInterval) {
        public RetryProperties {
            if (maxRetries <= 0) {
                maxRetries = 4;
            }
            if (initialInterval == null) {
                initialInterval = Duration.ofMillis(500);
            }
            if (multiplier <= 1) {
                multiplier = 2.0;
            }
            if (maxInterval == null) {
                maxInterval = Duration.ofSeconds(10);
            }
        }
    }
}
