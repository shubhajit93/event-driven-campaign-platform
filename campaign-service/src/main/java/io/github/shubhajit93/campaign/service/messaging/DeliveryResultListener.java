package io.github.shubhajit93.campaign.service.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.github.shubhajit93.campaign.api.DeliveryCompleted;
import io.github.shubhajit93.campaign.api.Queues;
import io.github.shubhajit93.campaign.service.application.CampaignService;
import org.springframework.stereotype.Component;

/**
 * Consumes delivery results. If processing throws, the message is not deleted. SQS makes it visible again
 * after the visibility timeout, and moves it to the DLQ once the redrive limit is reached.
 */
@Component
public class DeliveryResultListener {

    private final CampaignService campaignService;
    private final ObjectMapper objectMapper;

    public DeliveryResultListener(CampaignService campaignService, ObjectMapper objectMapper) {
        this.campaignService = campaignService;
        this.objectMapper = objectMapper;
    }

    @SqsListener(value = Queues.DELIVERY_COMPLETED, id = "delivery-results")
    public void onResult(String payload) throws JsonProcessingException {
        campaignService.recordResult(objectMapper.readValue(payload, DeliveryCompleted.class));
    }
}
