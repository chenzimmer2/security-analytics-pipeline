package com.akamai.miniwsa.ingestion.service;

import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.ingestion.dto.IngestionError;
import com.akamai.miniwsa.ingestion.dto.IngestionResponse;
import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import com.akamai.miniwsa.ingestion.mapper.SecurityEventMapper;
import com.akamai.miniwsa.repository.SecurityEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Single entry point for event ingestion.
 *
 * Atomicity contract: the entire batch succeeds or fails as a unit.
 *   - Any validation error  → 400 (nothing persisted).
 *   - Duplicate event_id    → DataIntegrityViolationException propagates;
 *     the transaction rolls back and @ControllerAdvice returns 409.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final ValidationService       validationService;
    private final SecurityEventMapper     mapper;
    private final SecurityEventRepository repository;

    @Transactional
    public IngestionResponse ingestAll(List<SecurityEventRequest> requests) {
        List<IngestionError> validationErrors = new ArrayList<>();
        List<SecurityEvent>  validEvents      = new ArrayList<>();

        for (SecurityEventRequest request : requests) {
            List<IngestionError> errors = validationService.validate(request);
            if (!errors.isEmpty()) {
                validationErrors.addAll(errors);
            } else {
                SecurityEvent event = mapper.toEntity(request);
                validEvents.add(event);
            }
        }

        if (!validationErrors.isEmpty()) {
            return IngestionResponse.builder()
                    .ingested(0)
                    .failed(validationErrors.size())
                    .errors(validationErrors)
                    .build();
        }

        // Single saveAll → one flush at commit time.
        // DataIntegrityViolationException (duplicate event_id) propagates naturally;
        // the TX is rolled back by Spring and @ControllerAdvice returns 409.
        repository.saveAll(validEvents);
        log.debug("Batch ingested {} events", validEvents.size());

        return IngestionResponse.builder()
                .ingested(validEvents.size())
                .failed(0)
                .errors(List.of())
                .build();
    }

}
