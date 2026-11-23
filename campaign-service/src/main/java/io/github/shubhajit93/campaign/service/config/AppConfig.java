package io.github.shubhajit93.campaign.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Queues are provisioned by Terraform (deploy/terraform/sqs.tf) or LocalStack locally, never by the app. */
@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
