package io.github.shubhajit93.campaign.worker.sender;

/** A failure that may succeed on retry. */
public class TransientDeliveryException extends RuntimeException {

    public TransientDeliveryException(String message) {
        super(message);
    }
}
