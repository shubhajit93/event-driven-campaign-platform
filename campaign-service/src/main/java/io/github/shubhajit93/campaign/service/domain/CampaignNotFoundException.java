package io.github.shubhajit93.campaign.service.domain;

import java.util.UUID;

public class CampaignNotFoundException extends RuntimeException {

    public CampaignNotFoundException(UUID id) {
        super("Campaign " + id + " not found");
    }
}
