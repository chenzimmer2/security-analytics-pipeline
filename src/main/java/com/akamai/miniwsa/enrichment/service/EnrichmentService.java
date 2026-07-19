package com.akamai.miniwsa.enrichment.service;

import com.akamai.miniwsa.domain.SecurityEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Orchestrates the enrichment pipeline: classify → score.
 * Mutates the SecurityEvent in-place before it is persisted.
 *
 * Both steps read from entity fields that were already set by
 * SecurityEventMapper, so the source DTO is no longer required here.
 * The repeat-offender cache is supplied by the caller (IngestionService)
 * to avoid per-event DB round-trips.
 */
@Service
@RequiredArgsConstructor
public class EnrichmentService {

    private final ClassificationService classificationService;
    private final ThreatScoringService  threatScoringService;

    public void enrich(SecurityEvent event, Map<String, Boolean> offenderCache) {
        event.setAttackType(classificationService.classify(event.getRuleCategory()));
        event.setThreatScore(threatScoringService.computeScore(event, offenderCache));
    }
}
