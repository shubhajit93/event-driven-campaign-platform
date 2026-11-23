package io.github.shubhajit93.campaign.api;

/**
 * SQS queue names shared by all services. Every queue has a dead-letter queue named {@code <queue>-dlq},
 * attached through a redrive policy (see {@code deploy/terraform/sqs.tf}).
 * Environments are kept apart by AWS account, so the names have no environment prefix.
 */
public final class Queues {

    public static final String EMAIL_REQUESTED = "delivery-email-requested";
    public static final String SMS_REQUESTED = "delivery-sms-requested";
    public static final String DELIVERY_COMPLETED = "delivery-completed";

    public static final String DLQ_SUFFIX = "-dlq";

    private Queues() {
    }

    public static String requestedFor(Channel channel) {
        return switch (channel) {
            case EMAIL -> EMAIL_REQUESTED;
            case SMS -> SMS_REQUESTED;
        };
    }
}
