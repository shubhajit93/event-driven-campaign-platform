package io.github.shubhajit93.campaign.service.web;

import io.github.shubhajit93.campaign.service.application.CampaignService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignService campaignService;

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @PostMapping
    public ResponseEntity<CampaignResponse> create(@Valid @RequestBody CreateCampaignRequest request) {
        CampaignResponse created = campaignService.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** 202: launching only schedules the deliveries. Progress is visible through GET. */
    @PostMapping("/{id}/launch")
    public ResponseEntity<CampaignResponse> launch(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(campaignService.launch(id));
    }

    @GetMapping("/{id}")
    public CampaignResponse get(@PathVariable UUID id) {
        return campaignService.get(id);
    }
}
