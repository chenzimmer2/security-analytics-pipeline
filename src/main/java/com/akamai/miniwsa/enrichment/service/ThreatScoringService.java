package com.akamai.miniwsa.enrichment.service;

import com.akamai.miniwsa.domain.SecurityEvent;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Computes a threat score 0-100.
 *
 * score = severityScore + actionScore + pathScore + repeatOffenderBonus
 * score = min(score, 100)
 *
 * The repeat-offender lookup is passed in as a pre-built cache so that
 * callers (IngestionService) can resolve all unique IPs in a single batch
 * query rather than issuing one COUNT query per event.
 */
@Service
public class ThreatScoringService {

    public int computeScore(SecurityEvent event, Map<String, Boolean> offenderCache) {
        int score = 0;
        score += severityScore(event.getRuleSeverity());
        score += actionScore(event.getAction());
        score += pathScore(event.getPath());
        if (offenderCache.getOrDefault(event.getClientIp(), false)) {
            score += 15;
        }
        return Math.min(score, 100);
    }

    private int severityScore(String severity) {
        if (severity == null) return 0;
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> 40;
            case "HIGH"     -> 30;
            case "MEDIUM"   -> 20;
            case "LOW"      -> 10;
            default         -> 0;
        };
    }

    private int actionScore(String action) {
        if (action == null) return 0;
        return switch (action.toUpperCase()) {
            case "DENY"    -> 20;
            case "ALERT"   -> 10;
            case "MONITOR" -> 0;
            default        -> 0;
        };
    }

    private int pathScore(String path) {
        if (path == null) return 0;
        String lower = path.toLowerCase();
        return (lower.contains("/admin") || lower.contains("/login")) ? 15 : 0;
    }
}
