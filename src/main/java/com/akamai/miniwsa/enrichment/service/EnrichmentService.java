package com.akamai.miniwsa.enrichment.service;

import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the enrichment pipeline: classify → score.
 * Mutates the SecurityEvent in-place before it is persisted.
 */
@Service
@RequiredArgsConstructor
public class EnrichmentService {

    private final ClassificationService classificationService;
    private final ThreatScoringService  threatScoringService;

    public void enrich(SecurityEvent event, SecurityEventRequest request) {
        String ruleCategory = request.getRule() != null ? request.getRule().getCategory() : null;
        event.setAttackType(classificationService.classify(ruleCategory));
        event.setThreatScore(threatScoringService.computeScore(request));
    }
}
