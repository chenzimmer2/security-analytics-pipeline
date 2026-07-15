package com.akamai.miniwsa.enrichment.service;

import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Computes a threat score 0-100.
 *
 * score = severityScore + actionScore + pathScore + repeatOffenderBonus
 * score = min(score, 100)
 */
@Service
@RequiredArgsConstructor
public class ThreatScoringService {

    private final RepeatOffenderDetectionService repeatOffenderDetectionService;

    public int computeScore(SecurityEventRequest request) {
        int score = 0;
        score += severityScore(request.getRule() != null ? request.getRule().getSeverity() : null);
        score += actionScore(request.getAction());
        score += pathScore(request.getPath());
        if (repeatOffenderDetectionService.isRepeatOffender(request.getClientIp())) {
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
