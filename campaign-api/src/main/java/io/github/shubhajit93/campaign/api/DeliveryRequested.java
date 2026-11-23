package io.github.shubhajit93.campaign.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by campaign-service (via the outbox) for every recipient and channel of a launched campaign.
 *
 * @param messageId unique per delivery; consumers use it as the idempotency key
 */
public record DeliveryRequested(
        UUID messageId,
        UUID campaignId,
        UUID recipientId,
        Channel channel,
        String address,
        String content,
        Instant requestedAt) {
}
