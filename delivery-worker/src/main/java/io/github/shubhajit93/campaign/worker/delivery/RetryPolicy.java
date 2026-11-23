package io.github.shubhajit93.campaign.worker.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Retry schedule for failed deliveries, applied by changing the message's SQS visibility timeout.
 * {@code maxAttempts} must equal the queue's redrive {@code maxReceiveCount}: after the last attempt fails,
 * SQS moves the message to the dead-letter queue instead of delivering it again.
 */
@ConfigurationProperties("delivery.retry")
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {

    /** SQS rejects visibility timeouts above 12 hours. */
    private static final long SQS_MAX_VISIBILITY_SECONDS = 43_200;

    public RetryPolicy {
        if (maxAttempts <= 0) {
            maxAttempts = 4;
        }
        if (initialBackoff == null) {
            initialBackoff = Duration.ofSeconds(2);
        }
        if (multiplier < 1) {
            multiplier = 2.0;
        }
        if (maxBackoff == null) {
            maxBackoff = Duration.ofMinutes(1);
        }
    }

    public boolean isLastAttempt(int attempt) {
        return attempt >= maxAttempts;
    }

    /** Exponential backoff in whole seconds: initial, initial × multiplier, ..., capped at maxBackoff. */
    public int backoffSeconds(int attempt) {
        double seconds = initialBackoff.toSeconds() * Math.pow(multiplier, Math.max(0, attempt - 1));
        long capped = Math.min((long) seconds, Math.min(maxBackoff.toSeconds(), SQS_MAX_VISIBILITY_SECONDS));
        return (int) Math.max(1, capped);
    }
}
