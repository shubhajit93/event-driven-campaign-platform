package io.github.shubhajit93.campaign.service.application;

import io.github.shubhajit93.campaign.api.DeliveryCompleted;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import io.github.shubhajit93.campaign.api.DeliveryStatus;
import io.github.shubhajit93.campaign.service.domain.Campaign;
import io.github.shubhajit93.campaign.service.domain.CampaignNotFoundException;
import io.github.shubhajit93.campaign.service.domain.CampaignRepository;
import io.github.shubhajit93.campaign.service.domain.CampaignStatus;
import io.github.shubhajit93.campaign.service.domain.ProcessedMessageStore;
import io.github.shubhajit93.campaign.service.outbox.OutboxWriter;
import io.github.shubhajit93.campaign.service.web.CampaignResponse;
import io.github.shubhajit93.campaign.service.web.CreateCampaignRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);

    private final CampaignRepository campaigns;
    private final OutboxWriter outbox;
    private final ProcessedMessageStore processedMessages;
    private final Clock clock;

    public CampaignService(CampaignRepository campaigns, OutboxWriter outbox,
                           ProcessedMessageStore processedMessages, Clock clock) {
        this.campaigns = campaigns;
        this.outbox = outbox;
        this.processedMessages = processedMessages;
        this.clock = clock;
    }

    @Transactional
    public CampaignResponse create(CreateCampaignRequest request) {
        Campaign campaign = Campaign.draft(request.name(), request.template(), request.channels(), clock.instant());
        request.recipients().forEach(r -> campaign.addRecipient(r.name(), r.email(), r.phone()));
        return CampaignResponse.from(campaigns.save(campaign));
    }

    /**
     * The status change and the fan-out events are committed in one local transaction.
     * There is no dual write to the database and Kafka, so a crash can never leave them out of sync.
     */
    @Transactional
    public CampaignResponse launch(UUID id) {
        Campaign campaign = campaigns.findByIdForUpdate(id).orElseThrow(() -> new CampaignNotFoundException(id));
        Instant now = clock.instant();
        List<DeliveryRequested> deliveries = campaign.launch(now);
        outbox.enqueue(deliveries, now);
        log.info("Launched campaign {} with {} deliveries", id, deliveries.size());
        return CampaignResponse.from(campaign);
    }

    @Transactional(readOnly = true)
    public CampaignResponse get(UUID id) {
        return campaigns.findById(id).map(CampaignResponse::from).orElseThrow(() -> new CampaignNotFoundException(id));
    }

    /**
     * Applies a delivery result exactly once, even though Kafka delivers it at least once.
     *
     * @return {@code false} if the result was a duplicate and was ignored
     */
    @Transactional
    public boolean recordResult(DeliveryCompleted result) {
        Instant now = clock.instant();
        if (!processedMessages.markProcessed(result.messageId(), now)) {
            log.debug("Ignoring duplicate result for message {}", result.messageId());
            return false;
        }
        if (result.status() == DeliveryStatus.DELIVERED) {
            campaigns.incrementDelivered(result.campaignId());
        } else {
            campaigns.incrementFailed(result.campaignId());
        }
        campaigns.completeIfFinished(result.campaignId(), now, CampaignStatus.LAUNCHED, CampaignStatus.COMPLETED);
        return true;
    }
}
