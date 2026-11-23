package io.github.shubhajit93.campaign.service.domain;

import io.github.shubhajit93.campaign.api.Channel;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "recipients")
public class Recipient {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;

    private String name;
    private String email;
    private String phone;

    protected Recipient() {
    }

    Recipient(Campaign campaign, String name, String email, String phone) {
        this.id = UUID.randomUUID();
        this.campaign = campaign;
        this.name = name;
        this.email = email;
        this.phone = phone;
    }

    /** The address to use for a channel, or {@code null} when this recipient cannot be reached on it. */
    public String addressFor(Channel channel) {
        String address = switch (channel) {
            case EMAIL -> email;
            case SMS -> phone;
        };
        return address == null || address.isBlank() ? null : address;
    }

    Map<String, String> templateVariables() {
        return Map.of("name", name);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
