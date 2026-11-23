package io.github.shubhajit93.campaign.worker.sender;

import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.api.DeliveryRequested;

/**
 * Port to an external provider (email service, SMS gateway, ...).
 * Implementations signal failures by type, which decides between retrying and giving up.
 */
public interface ChannelSender {

    Channel channel();

    /**
     * @throws TransientDeliveryException for failures worth retrying (timeouts, throttling, 5xx)
     * @throws PermanentDeliveryException for failures that will never succeed (invalid address, rejected content)
     */
    void send(DeliveryRequested delivery);
}
