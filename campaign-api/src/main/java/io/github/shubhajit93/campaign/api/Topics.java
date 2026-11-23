package io.github.shubhajit93.campaign.api;

/** Kafka topic names shared by all services. */
public final class Topics {

    public static final String EMAIL_REQUESTED = "delivery.email.requested";
    public static final String SMS_REQUESTED = "delivery.sms.requested";
    public static final String DELIVERY_COMPLETED = "delivery.completed";

    /** Suffix used by Spring Kafka's dead-letter publishing recoverer. */
    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
    }

    public static String requestedFor(Channel channel) {
        return switch (channel) {
            case EMAIL -> EMAIL_REQUESTED;
            case SMS -> SMS_REQUESTED;
        };
    }
}
