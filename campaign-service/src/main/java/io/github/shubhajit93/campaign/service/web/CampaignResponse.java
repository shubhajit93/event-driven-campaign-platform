package io.github.shubhajit93.campaign.service.web;

import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.service.domain.Campaign;
import io.github.shubhajit93.campaign.service.domain.CampaignStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record CampaignResponse(
        UUID id,
        String name,
        CampaignStatus status,
        Set<Channel> channels,
        int recipients,
        int expectedDeliveries,
        int delivered,
        int failed,
        Instant createdAt,
        Instant launchedAt,
        Instant completedAt) {

    public static CampaignResponse from(Campaign c) {
        return new CampaignResponse(c.getId(), c.getName(), c.getStatus(), c.getChannels(), c.getRecipientCount(),
                c.getExpectedDeliveries(), c.getDelivered(), c.getFailed(),
                c.getCreatedAt(), c.getLaunchedAt(), c.getCompletedAt());
    }
}
