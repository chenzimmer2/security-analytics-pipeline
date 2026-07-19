package com.akamai.miniwsa.stats.controller;

import com.akamai.miniwsa.stats.dto.SummaryResponse;
import com.akamai.miniwsa.stats.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Exposes the stats summary endpoint.
 *
 * Validation rules enforced here (before reaching the service):
 *   - 'from' and 'to' are required — prevents unbounded full-table scans.
 *   - Both must be valid ISO-8601 timestamps.
 *   - 'from' must not be after 'to'.
 */
@RestController
@RequestMapping("/v1/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/summary")
    public ResponseEntity<?> summary(
            @RequestParam(required = false) Long configId,
            @RequestParam String from,
            @RequestParam String to) {

        Instant fromInstant;
        Instant toInstant;

        try {
            fromInstant = Instant.parse(from);
            toInstant   = Instant.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", "Invalid timestamp format — use ISO-8601 (e.g. 2026-05-20T14:32:10Z)"));
        }

        if (fromInstant.isAfter(toInstant)) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", "'from' must not be after 'to'"));
        }

        SummaryResponse response = statsService.getSummary(configId, fromInstant, toInstant);
        return ResponseEntity.ok(response);
    }
}
