package io.github.shubhajit93.campaign.service.config;

import io.github.shubhajit93.campaign.api.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Topics this service produces to or consumes from. In production they would be managed by IaC. */
    @Bean
    KafkaAdmin.NewTopics campaignTopics(@Value("${app.kafka.partitions:3}") int partitions,
                                        @Value("${app.kafka.replicas:1}") int replicas) {
        return new KafkaAdmin.NewTopics(
                topic(Topics.EMAIL_REQUESTED, partitions, replicas),
                topic(Topics.SMS_REQUESTED, partitions, replicas),
                topic(Topics.DELIVERY_COMPLETED, partitions, replicas));
    }

    private static NewTopic topic(String name, int partitions, int replicas) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
    }
}
