package io.github.shubhajit93.campaign.service.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    private UUID aggregateId;

    /** Name of the SQS queue the event goes to. */
    private String destination;

    @Column(columnDefinition = "text")
    private String payload;

    private Instant createdAt;
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID id, UUID aggregateId, String destination, String payload, Instant createdAt) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.destination = destination;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    void markPublished(Instant at) {
        this.publishedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public String getDestination() {
        return destination;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
