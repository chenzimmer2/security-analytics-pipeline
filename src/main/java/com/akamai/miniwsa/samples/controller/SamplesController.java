package com.akamai.miniwsa.samples.controller;

import com.akamai.miniwsa.samples.dto.SamplesResponse;
import com.akamai.miniwsa.samples.service.SamplesService;
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
 * Exposes the samples endpoint.
 *
 * All filter parameters are optional per the assignment spec.
 * Validation enforced here:
 *   - from/to: each is independently optional; if provided must be valid ISO-8601.
 *   - If both are provided, from must not be after to.
 *   - limit: 1–100 (default 20).
 *   - offset: >= 0 (default 0).
 */
@RestController
@RequestMapping("/v1/events")
@RequiredArgsConstructor
public class SamplesController {

    private final SamplesService samplesService;

    @GetMapping("/samples")
    public ResponseEntity<?> samples(
            @RequestParam(required = false) Long configId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String action,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false, defaultValue = "0")  int offset) {

        if (limit < 1 || limit > SamplesService.MAX_LIMIT) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", "'limit' must be between 1 and " + SamplesService.MAX_LIMIT));
        }
        if (offset < 0) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", "'offset' must be >= 0"));
        }

        Instant fromInstant = null;
        Instant toInstant   = null;

        try {
            if (from != null) fromInstant = Instant.parse(from);
            if (to   != null) toInstant   = Instant.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", "Invalid timestamp format — use ISO-8601 (e.g. 2026-05-20T14:32:10Z)"));
        }

        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", "'from' must not be after 'to'"));
        }

        SamplesResponse response = samplesService.getSamples(
                configId, fromInstant, toInstant, category, action, limit, offset);

        return ResponseEntity.ok(response);
    }
}
