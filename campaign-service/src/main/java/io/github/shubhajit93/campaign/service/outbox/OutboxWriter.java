package io.github.shubhajit93.campaign.service.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import io.github.shubhajit93.campaign.api.Topics;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Stores events in the same transaction as the state change that produced them. */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** MANDATORY: writing to the outbox outside the business transaction would defeat its purpose. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(List<DeliveryRequested> deliveries, Instant now) {
        List<OutboxEvent> events = deliveries.stream()
                .map(d -> new OutboxEvent(d.messageId(), d.campaignId(), Topics.requestedFor(d.channel()),
                        d.recipientId().toString(), toJson(d), now))
                .toList();
        repository.saveAll(events);
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + event.getClass().getSimpleName(), e);
        }
    }
}
