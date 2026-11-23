package io.github.shubhajit93.campaign.service.web;

import io.github.shubhajit93.campaign.api.Channel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

public record CreateCampaignRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 5000) String template,
        @NotEmpty Set<Channel> channels,
        @NotEmpty @Size(max = 10_000) List<@Valid RecipientRequest> recipients) {

    public record RecipientRequest(
            @NotBlank @Size(max = 200) String name,
            @Email String email,
            @Size(max = 20) String phone) {
    }
}
