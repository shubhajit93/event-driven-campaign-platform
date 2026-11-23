package io.github.shubhajit93.campaign.service.domain;

/** Thrown when an operation is not allowed in the campaign's current status. */
public class CampaignStateException extends RuntimeException {

    public CampaignStateException(String message) {
        super(message);
    }
}
