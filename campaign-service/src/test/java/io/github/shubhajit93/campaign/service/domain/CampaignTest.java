package io.github.shubhajit93.campaign.service.domain;

import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void launchFansOutOneDeliveryPerRecipientPerReachableChannel() {
        Campaign campaign = Campaign.draft("Spring sale", "Hi {{name}}!", Set.of(Channel.EMAIL, Channel.SMS), NOW);
        campaign.addRecipient("Alice", "alice@example.com", "+8801700000000");
        campaign.addRecipient("Bob", "bob@example.com", null);
        campaign.addRecipient("Carol", " ", "+8801800000000");

        List<DeliveryRequested> deliveries = campaign.launch(NOW);

        Map<Channel, Long> perChannel = deliveries.stream()
                .collect(Collectors.groupingBy(DeliveryRequested::channel, Collectors.counting()));
        assertThat(perChannel).containsEntry(Channel.EMAIL, 2L).containsEntry(Channel.SMS, 2L);
        assertThat(deliveries).extracting(DeliveryRequested::messageId).doesNotHaveDuplicates();
        assertThat(campaign.getExpectedDeliveries()).isEqualTo(4);
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.LAUNCHED);
    }

    @Test
    void launchRendersTemplatePerRecipient() {
        Campaign campaign = Campaign.draft("Welcome", "Hello {{ name }}", Set.of(Channel.EMAIL), NOW);
        campaign.addRecipient("Alice", "alice@example.com", null);

        assertThat(campaign.launch(NOW)).singleElement()
                .extracting(DeliveryRequested::content).isEqualTo("Hello Alice");
    }

    @Test
    void campaignCannotBeLaunchedTwice() {
        Campaign campaign = Campaign.draft("Once", "x", Set.of(Channel.EMAIL), NOW);
        campaign.addRecipient("Alice", "alice@example.com", null);
        campaign.launch(NOW);

        assertThatThrownBy(() -> campaign.launch(NOW)).isInstanceOf(CampaignStateException.class);
        assertThatThrownBy(() -> campaign.addRecipient("Late", "late@example.com", null))
                .isInstanceOf(CampaignStateException.class);
    }

    @Test
    void campaignWithNoReachableRecipientsCompletesImmediately() {
        Campaign campaign = Campaign.draft("Nobody", "x", Set.of(Channel.SMS), NOW);
        campaign.addRecipient("Alice", "alice@example.com", null);

        assertThat(campaign.launch(NOW)).isEmpty();
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.COMPLETED);
        assertThat(campaign.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void campaignNeedsAtLeastOneChannel() {
        assertThatThrownBy(() -> Campaign.draft("None", "x", Set.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
