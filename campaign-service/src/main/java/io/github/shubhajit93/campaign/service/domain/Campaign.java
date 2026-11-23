package io.github.shubhajit93.campaign.service.domain;

import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate root. Launching is a pure domain operation that returns the deliveries to publish.
 * Persisting them atomically with the status change is the job of the application service (via the outbox).
 */
@Entity
@Table(name = "campaigns")
public class Campaign {

    @Id
    private UUID id;

    private String name;

    @Column(columnDefinition = "text")
    private String template;

    @Enumerated(EnumType.STRING)
    private CampaignStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "campaign_channels", joinColumns = @JoinColumn(name = "campaign_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "channel")
    private Set<Channel> channels = EnumSet.noneOf(Channel.class);

    @OneToMany(mappedBy = "campaign", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Recipient> recipients = new ArrayList<>();

    private int expectedDeliveries;
    private int delivered;
    private int failed;

    private Instant createdAt;
    private Instant launchedAt;
    private Instant completedAt;

    protected Campaign() {
    }

    public static Campaign draft(String name, String template, Collection<Channel> channels, Instant now) {
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("A campaign needs at least one channel");
        }
        Campaign campaign = new Campaign();
        campaign.id = UUID.randomUUID();
        campaign.name = name;
        campaign.template = template;
        campaign.channels = EnumSet.copyOf(channels);
        campaign.status = CampaignStatus.DRAFT;
        campaign.createdAt = now;
        return campaign;
    }

    public Recipient addRecipient(String name, String email, String phone) {
        requireStatus(CampaignStatus.DRAFT, "add recipients to");
        Recipient recipient = new Recipient(this, name, email, phone);
        recipients.add(recipient);
        return recipient;
    }

    /**
     * Fans the campaign out into one delivery per recipient per reachable channel.
     * A recipient without an address for a channel is skipped for that channel only.
     */
    public List<DeliveryRequested> launch(Instant now) {
        requireStatus(CampaignStatus.DRAFT, "launch");
        List<DeliveryRequested> deliveries = new ArrayList<>();
        for (Recipient recipient : recipients) {
            String content = TemplateRenderer.render(template, recipient.templateVariables());
            for (Channel channel : EnumSet.copyOf(channels)) {
                String address = recipient.addressFor(channel);
                if (address != null) {
                    deliveries.add(new DeliveryRequested(UUID.randomUUID(), id, recipient.getId(),
                            channel, address, content, now));
                }
            }
        }
        expectedDeliveries = deliveries.size();
        launchedAt = now;
        if (deliveries.isEmpty()) {
            status = CampaignStatus.COMPLETED;
            completedAt = now;
        } else {
            status = CampaignStatus.LAUNCHED;
        }
        return deliveries;
    }

    private void requireStatus(CampaignStatus expected, String action) {
        if (status != expected) {
            throw new CampaignStateException("Cannot " + action + " a campaign in status " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public Set<Channel> getChannels() {
        return Set.copyOf(channels);
    }

    public int getRecipientCount() {
        return recipients.size();
    }

    public int getExpectedDeliveries() {
        return expectedDeliveries;
    }

    public int getDelivered() {
        return delivered;
    }

    public int getFailed() {
        return failed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLaunchedAt() {
        return launchedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
