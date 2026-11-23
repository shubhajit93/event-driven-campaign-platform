package io.github.shubhajit93.campaign.worker.delivery;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(4, Duration.ofSeconds(2), 2.0, Duration.ofSeconds(10));

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        assertThat(policy.backoffSeconds(1)).isEqualTo(2);
        assertThat(policy.backoffSeconds(2)).isEqualTo(4);
        assertThat(policy.backoffSeconds(3)).isEqualTo(8);
        assertThat(policy.backoffSeconds(4)).isEqualTo(10);
        assertThat(policy.backoffSeconds(30)).isEqualTo(10);
    }

    @Test
    void lastAttemptMatchesMaxAttempts() {
        assertThat(policy.isLastAttempt(3)).isFalse();
        assertThat(policy.isLastAttempt(4)).isTrue();
        assertThat(policy.isLastAttempt(5)).isTrue();
    }

    @Test
    void defaultsApplyWhenNotConfigured() {
        RetryPolicy defaults = new RetryPolicy(0, null, 0, null);
        assertThat(defaults.maxAttempts()).isEqualTo(4);
        assertThat(defaults.backoffSeconds(1)).isEqualTo(2);
    }
}
