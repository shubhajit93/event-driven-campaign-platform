package io.github.shubhajit93.campaign.worker.config;

import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.worker.sender.ChannelSender;
import io.github.shubhajit93.campaign.worker.sender.SimulatedSender;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Wires the simulated providers. Swapping in a real provider means adding one ChannelSender bean. */
@Configuration(proxyBeanMethods = false)
public class SenderConfig {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern E164_PHONE = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    @Bean
    ChannelSender emailSender(SimulationProperties simulation) {
        return new SimulatedSender(Channel.EMAIL, EMAIL, simulation.transientFailureRate(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    ChannelSender smsSender(SimulationProperties simulation) {
        return new SimulatedSender(Channel.SMS, E164_PHONE, simulation.transientFailureRate(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    @ConfigurationProperties("delivery.simulation")
    public record SimulationProperties(double transientFailureRate) {
    }
}
