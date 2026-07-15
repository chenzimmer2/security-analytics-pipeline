package com.akamai.miniwsa.ingestion.service;

import com.akamai.miniwsa.ingestion.dto.IngestionError;
import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Wraps Jakarta Bean Validation so the same logic can be reused
 * by both the REST controller path and the Kafka consumer path.
 */
@Service
@RequiredArgsConstructor
public class ValidationService {

    private final Validator validator;

    /**
     * Returns an empty list when the request is valid;
     * otherwise a list of field-level errors.
     */
    public List<IngestionError> validate(SecurityEventRequest request) {
        Set<ConstraintViolation<SecurityEventRequest>> violations = validator.validate(request);
        return violations.stream()
                .map(v -> new IngestionError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
    }
}
