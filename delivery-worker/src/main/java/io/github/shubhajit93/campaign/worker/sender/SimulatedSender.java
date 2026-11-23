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
 * <p>
 * Like payment providers' test card numbers, any address containing {@value #ALWAYS_TRANSIENT_MARKER}
 * always fails transiently, for example {@code someone@flaky.example}.
 */
public class SimulatedSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSender.class);
    public static final String ALWAYS_TRANSIENT_MARKER = "flaky";

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
        if (delivery.address().contains(ALWAYS_TRANSIENT_MARKER) || random.getAsDouble() < transientFailureRate) {
            throw new TransientDeliveryException(channel + " provider temporarily unavailable");
        }
        log.info("Delivered {} message {} for campaign {}", channel, delivery.messageId(), delivery.campaignId());
    }
}
