package io.github.shubhajit93.campaign.worker.sender;

/** A failure that will never succeed, so retrying it only wastes provider quota. */
public class PermanentDeliveryException extends RuntimeException {

    public PermanentDeliveryException(String message) {
        super(message);
    }
}
