package io.github.shubhajit93.campaign.worker.delivery;

import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.api.DeliveryCompleted;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import io.github.shubhajit93.campaign.api.DeliveryStatus;
import io.github.shubhajit93.campaign.worker.sender.SimulatedSender;
import io.github.shubhajit93.campaign.worker.sender.TransientDeliveryException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final ProcessedMessageStore processed = mock(ProcessedMessageStore.class);
    private final ResultPublisher results = mock(ResultPublisher.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    private DeliveryService service(double failureRate) {
        var email = new SimulatedSender(Channel.EMAIL, Pattern.compile(".+@.+"), failureRate, () -> 0.5);
        return new DeliveryService(List.of(email), processed, results, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DeliveryRequested email(String address) {
        return new DeliveryRequested(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Channel.EMAIL, address, "Hello", NOW);
    }

    @Test
    void successfulDeliveryPublishesDeliveredResult() {
        DeliveryRequested request = email("a@example.com");
        when(processed.markProcessed(request.messageId(), NOW)).thenReturn(true);

        service(0.0).process(request);

        ArgumentCaptor<DeliveryCompleted> result = ArgumentCaptor.forClass(DeliveryCompleted.class);
        verify(results).publish(result.capture());
        assertThat(result.getValue().status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(result.getValue().messageId()).isEqualTo(request.messageId());
    }

    @Test
    void duplicateIsSkippedWithoutSendingOrPublishing() {
        DeliveryRequested request = email("a@example.com");
        when(processed.markProcessed(request.messageId(), NOW)).thenReturn(false);

        service(0.0).process(request);

        verify(results, never()).publish(any());
        assertThat(metrics.counter("campaign.deliveries.duplicates", "channel", "EMAIL").count()).isEqualTo(1.0);
    }

    @Test
    void permanentFailureIsReportedWithoutRetry() {
        DeliveryRequested request = email("not-an-address");
        when(processed.markProcessed(eq(request.messageId()), any())).thenReturn(true);

        service(0.0).process(request);

        ArgumentCaptor<DeliveryCompleted> result = ArgumentCaptor.forClass(DeliveryCompleted.class);
        verify(results).publish(result.capture());
        assertThat(result.getValue().status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(result.getValue().failureReason()).contains("Invalid");
    }

    @Test
    void transientFailurePropagatesSoTheErrorHandlerRetries() {
        DeliveryRequested request = email("a@example.com");
        when(processed.markProcessed(eq(request.messageId()), any())).thenReturn(true);

        assertThatThrownBy(() -> service(1.0).process(request)).isInstanceOf(TransientDeliveryException.class);
        verify(results, never()).publish(any());
    }

    @Test
    void exhaustedDeliveryIsReportedAsFailed() {
        DeliveryRequested request = email("a@example.com");

        service(0.0).reportExhausted(request, "provider down");

        ArgumentCaptor<DeliveryCompleted> result = ArgumentCaptor.forClass(DeliveryCompleted.class);
        verify(results).publish(result.capture());
        assertThat(result.getValue().status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(result.getValue().failureReason()).isEqualTo("Retries exhausted: provider down");
    }
}
