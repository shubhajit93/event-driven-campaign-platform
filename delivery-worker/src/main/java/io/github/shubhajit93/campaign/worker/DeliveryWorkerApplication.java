package io.github.shubhajit93.campaign.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DeliveryWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryWorkerApplication.class, args);
    }
}
