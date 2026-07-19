package com.akamai.miniwsa.ingestion.service;

import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.enrichment.service.EnrichmentService;
import com.akamai.miniwsa.enrichment.service.RepeatOffenderDetectionService;
import com.akamai.miniwsa.ingestion.dto.IngestionError;
import com.akamai.miniwsa.ingestion.dto.IngestionResponse;
import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import com.akamai.miniwsa.ingestion.mapper.SecurityEventMapper;
import com.akamai.miniwsa.repository.SecurityEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Single entry point for event ingestion.
 *
 * REST path — {@link #ingestAll}: all-or-nothing batch semantics inside one
 * transaction. Any validation failure rejects the entire batch (HTTP 400).
 * Duplicate event_id causes a DataIntegrityViolationException that propagates
 * to GlobalExceptionHandler.
 *
 * Kafka path — {@link #processKafkaBatch}: per-event fault isolation (log and
 * skip). Each valid event is saved in its own transaction so a single bad
 * message never blocks the rest of the poll batch.
 *
 * Both paths share {@link #buildOffenderCache} to resolve repeat-offender
 * status for every unique client IP in one query per IP — not one per event.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final ValidationService              validationService;
    private final SecurityEventMapper            mapper;
    private final EnrichmentService              enrichmentService;
    private final SecurityEventRepository        repository;
    private final RepeatOffenderDetectionService repeatOffenderService;

    // ── REST path ─────────────────────────────────────────────────────────────

    @Transactional
    public IngestionResponse ingestAll(List<SecurityEventRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return IngestionResponse.builder()
                    .ingested(0)
                    .failed(0)
                    .errors(List.of(new IngestionError(
                            -1, "body", "Batch must contain at least one event")))
                    .build();
        }

        List<IngestionError> validationErrors = new ArrayList<>();
        List<SecurityEvent>  validEvents      = new ArrayList<>();
        int rejectedEvents = 0;

        for (int i = 0; i < requests.size(); i++) {
            List<IngestionError> errors = validationService.validate(requests.get(i), i);
            if (!errors.isEmpty()) {
                rejectedEvents++;
                validationErrors.addAll(errors);
            } else {
                validEvents.add(mapper.toEntity(requests.get(i)));
            }
        }

        if (!validationErrors.isEmpty()) {
            return IngestionResponse.builder()
                    .ingested(0)
                    .failed(rejectedEvents)
                    .errors(validationErrors)
                    .build();
        }

        Map<String, Boolean> offenderCache = buildOffenderCache(validEvents);
        for (SecurityEvent event : validEvents) {
            enrichmentService.enrich(event, offenderCache);
        }

        repository.saveAll(validEvents);
        log.debug("Batch ingested {} events", validEvents.size());

        return IngestionResponse.builder()
                .ingested(validEvents.size())
                .failed(0)
                .errors(List.of())
                .build();
    }

    // ── Kafka path ────────────────────────────────────────────────────────────

    /**
     * Processes a Kafka poll batch with per-event fault isolation.
     * Invalid and duplicate events are logged and skipped; the rest are saved.
     * Each {@code repository.save()} runs in its own transaction (from
     * {@code SimpleJpaRepository}), so no single failure aborts the batch.
     */
    public void processKafkaBatch(List<SecurityEventRequest> requests) {
        if (requests.isEmpty()) return;

        List<SecurityEvent> validEvents = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            List<IngestionError> errors = validationService.validate(requests.get(i), i);
            if (!errors.isEmpty()) {
                log.warn("[Kafka] Validation failed for eventId={}: {}",
                        requests.get(i).getEventId(), errors);
            } else {
                validEvents.add(mapper.toEntity(requests.get(i)));
            }
        }

        if (validEvents.isEmpty()) return;

        Map<String, Boolean> offenderCache = buildOffenderCache(validEvents);

        for (SecurityEvent event : validEvents) {
            enrichmentService.enrich(event, offenderCache);
            try {
                repository.save(event);
                log.debug("[Kafka] Ingested eventId={}", event.getEventId());
            } catch (DataIntegrityViolationException e) {
                log.debug("[Kafka] Duplicate eventId={} — skipping (idempotent re-delivery)",
                        event.getEventId());
            } catch (Exception e) {
                log.error("[Kafka] Failed to persist eventId={}: {}",
                        event.getEventId(), e.getMessage());
            }
        }
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /**
     * Resolves repeat-offender status for every unique client IP in the list —
     * one COUNT query per unique IP, not one per event.
     */
    private Map<String, Boolean> buildOffenderCache(List<SecurityEvent> events) {
        Set<String> uniqueIps = events.stream()
                .map(SecurityEvent::getClientIp)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return uniqueIps.stream()
                .collect(Collectors.toMap(
                        ip -> ip,
                        repeatOffenderService::isRepeatOffender));
    }
}
