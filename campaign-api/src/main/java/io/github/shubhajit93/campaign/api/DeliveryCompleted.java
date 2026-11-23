package io.github.shubhajit93.campaign.api;

import java.time.Instant;
import java.util.UUID;

/** Published by delivery-worker once a delivery has succeeded or permanently failed. */
public record DeliveryCompleted(
        UUID messageId,
        UUID campaignId,
        Channel channel,
        DeliveryStatus status,
        String failureReason,
        Instant completedAt) {

    public static DeliveryCompleted delivered(DeliveryRequested request, Instant at) {
        return new DeliveryCompleted(request.messageId(), request.campaignId(), request.channel(),
                DeliveryStatus.DELIVERED, null, at);
    }

    public static DeliveryCompleted failed(DeliveryRequested request, String reason, Instant at) {
        return new DeliveryCompleted(request.messageId(), request.campaignId(), request.channel(),
                DeliveryStatus.FAILED, reason, at);
    }
}
