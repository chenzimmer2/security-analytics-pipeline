package com.akamai.miniwsa.ingestion.controller;

import com.akamai.miniwsa.ingestion.dto.IngestionResponse;
import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import com.akamai.miniwsa.ingestion.service.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Accepts both a single JSON object and a JSON array on the same endpoint.
 * Jackson unwraps a single object into a one-element list automatically via
 * spring.jackson.deserialization.accept-single-value-as-array=true.
 *
 * HTTP contract:
 *   201 Created     — all events persisted successfully.
 *   400 Bad Request — one or more events failed validation; nothing saved.
 *   409 Conflict    — duplicate eventId detected; batch rolled back
 *                     (handled by GlobalExceptionHandler).
 */
@RestController
@RequestMapping("/v1/events")
@RequiredArgsConstructor
public class EventIngestionController {

    private final IngestionService ingestionService;

    @PostMapping("/ingest")
    public ResponseEntity<IngestionResponse> ingest(@RequestBody List<SecurityEventRequest> requests) {
        IngestionResponse response = ingestionService.ingestAll(requests);

        if (response.getFailed() > 0) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
