package io.github.shubhajit93.campaign.worker.sender;

import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * Stand-in for a real provider. It validates addresses the way a provider would, and can inject
 * transient failures, so the retry and dead-letter paths can be exercised locally.
 */
public class SimulatedSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSender.class);

    private final Channel channel;
    private final Pattern validAddress;
    private final double transientFailureRate;
    private final DoubleSupplier random;

    public SimulatedSender(Channel channel, Pattern validAddress, double transientFailureRate, DoubleSupplier random) {
        this.channel = channel;
        this.validAddress = validAddress;
        this.transientFailureRate = transientFailureRate;
        this.random = random;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public void send(DeliveryRequested delivery) {
        if (!validAddress.matcher(delivery.address()).matches()) {
            throw new PermanentDeliveryException("Invalid " + channel + " address");
        }
        if (random.getAsDouble() < transientFailureRate) {
            throw new TransientDeliveryException(channel + " provider temporarily unavailable");
        }
        log.info("Delivered {} message {} for campaign {}", channel, delivery.messageId(), delivery.campaignId());
    }
}
