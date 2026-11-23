package io.github.shubhajit93.campaign.worker.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import io.github.shubhajit93.campaign.api.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * One listener container per channel. Each channel has its own concurrency, so a slow SMS provider
 * never holds up email delivery.
 */
@Component
public class DeliveryListener {

    private final DeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    public DeliveryListener(DeliveryService deliveryService, ObjectMapper objectMapper) {
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "email-delivery", topics = Topics.EMAIL_REQUESTED,
            concurrency = "${delivery.email.concurrency:3}")
    public void onEmail(String payload) throws JsonProcessingException {
        deliveryService.process(objectMapper.readValue(payload, DeliveryRequested.class));
    }

    @KafkaListener(id = "sms-delivery", topics = Topics.SMS_REQUESTED,
            concurrency = "${delivery.sms.concurrency:2}")
    public void onSms(String payload) throws JsonProcessingException {
        deliveryService.process(objectMapper.readValue(payload, DeliveryRequested.class));
    }
}
