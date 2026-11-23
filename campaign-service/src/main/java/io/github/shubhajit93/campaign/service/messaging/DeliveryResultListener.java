package io.github.shubhajit93.campaign.service.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shubhajit93.campaign.api.DeliveryCompleted;
import io.github.shubhajit93.campaign.api.Topics;
import io.github.shubhajit93.campaign.service.application.CampaignService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DeliveryResultListener {

    private final CampaignService campaignService;
    private final ObjectMapper objectMapper;

    public DeliveryResultListener(CampaignService campaignService, ObjectMapper objectMapper) {
        this.campaignService = campaignService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "delivery-results", topics = Topics.DELIVERY_COMPLETED)
    public void onResult(String payload) throws JsonProcessingException {
        campaignService.recordResult(objectMapper.readValue(payload, DeliveryCompleted.class));
    }
}
