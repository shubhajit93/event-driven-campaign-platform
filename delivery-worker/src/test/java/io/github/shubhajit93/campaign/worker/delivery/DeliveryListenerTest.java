package io.github.shubhajit93.campaign.worker.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.awspring.cloud.sqs.listener.Visibility;
import io.github.shubhajit93.campaign.api.Channel;
import io.github.shubhajit93.campaign.api.DeliveryRequested;
import io.github.shubhajit93.campaign.worker.sender.TransientDeliveryException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DeliveryListenerTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final DeliveryService service = mock(DeliveryService.class);
    private final Visibility visibility = mock(Visibility.class);
    private final DeliveryListener listener = new DeliveryListener(service,
            new RetryPolicy(3, Duration.ofSeconds(2), 2.0, Duration.ofSeconds(60)), json);

    private final DeliveryRequested request = new DeliveryRequested(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), Channel.EMAIL, "a@example.com", "Hi", Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void successLeavesVisibilityAlone() throws Exception {
        listener.handle(json.writeValueAsString(request), 1, visibility);

        verify(service).process(request);
        verifyNoInteractions(visibility);
    }

    @Test
    void earlyFailureSchedulesBackoffAndRethrows() throws Exception {
        doThrow(new TransientDeliveryException("timeout")).when(service).process(any());

        assertThatThrownBy(() -> listener.handle(json.writeValueAsString(request), 2, visibility))
                .isInstanceOf(TransientDeliveryException.class);

        verify(visibility).changeTo(4);
        verify(service, never()).reportExhausted(any(), any());
    }

    @Test
    void finalFailureReportsExhaustedAndReleasesToDlq() throws Exception {
        doThrow(new TransientDeliveryException("timeout")).when(service).process(any());

        assertThatThrownBy(() -> listener.handle(json.writeValueAsString(request), 3, visibility))
                .isInstanceOf(TransientDeliveryException.class);

        verify(service).reportExhausted(eq(request), startsWith("timeout"));
        verify(visibility).changeTo(0);
    }

    @Test
    void unreadablePayloadGoesStraightToDlq() {
        assertThatThrownBy(() -> listener.handle("{not json", 1, visibility))
                .isInstanceOf(IllegalArgumentException.class);

        verify(visibility).changeTo(0);
        verifyNoInteractions(service);
    }
}
