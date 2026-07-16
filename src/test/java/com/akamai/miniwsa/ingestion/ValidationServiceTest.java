package com.akamai.miniwsa.ingestion;

import com.akamai.miniwsa.ingestion.dto.IngestionError;
import com.akamai.miniwsa.ingestion.dto.RuleDto;
import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import com.akamai.miniwsa.ingestion.service.ValidationService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests — no Spring context.
 * The Validator is built directly from the Jakarta API.
 */
class ValidationServiceTest {

    private static ValidationService service;

    @BeforeAll
    static void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        service = new ValidationService(validator);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private SecurityEventRequest validRequest() {
        RuleDto rule = new RuleDto();
        rule.setSeverity("HIGH");
        rule.setCategory("INJECTION");

        SecurityEventRequest req = new SecurityEventRequest();
        req.setEventId("evt-valid-001");
        req.setEventTimestamp(Instant.parse("2026-07-16T10:00:00Z"));
        req.setConfigId(14227L);
        req.setClientIp("1.2.3.4");
        req.setRule(rule);
        req.setAction("DENY");
        return req;
    }

    private List<String> fieldNames(List<IngestionError> errors) {
        return errors.stream().map(IngestionError::getField).toList();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void validRequest_producesNoErrors() {
        List<IngestionError> errors = service.validate(validRequest(), 0);
        assertThat(errors).isEmpty();
    }

    @Test
    void missingEventId_producesFieldError() {
        SecurityEventRequest req = validRequest();
        req.setEventId(null);
        List<IngestionError> errors = service.validate(req, 0);
        assertThat(fieldNames(errors)).contains("eventId");
    }

    @Test
    void blankEventId_producesFieldError() {
        SecurityEventRequest req = validRequest();
        req.setEventId("   ");
        List<IngestionError> errors = service.validate(req, 0);
        assertThat(fieldNames(errors)).contains("eventId");
    }

    @Test
    void missingClientIp_producesFieldError() {
        SecurityEventRequest req = validRequest();
        req.setClientIp(null);
        List<IngestionError> errors = service.validate(req, 0);
        assertThat(fieldNames(errors)).contains("clientIp");
    }

    @Test
    void missingConfigId_producesFieldError() {
        SecurityEventRequest req = validRequest();
        req.setConfigId(null);
        List<IngestionError> errors = service.validate(req, 0);
        assertThat(fieldNames(errors)).contains("configId");
    }

    @Test
    void missingTimestamp_producesFieldError() {
        SecurityEventRequest req = validRequest();
        req.setEventTimestamp(null);
        List<IngestionError> errors = service.validate(req, 0);
        // constraint is on the Java field "eventTimestamp"
        assertThat(fieldNames(errors)).contains("eventTimestamp");
    }

    @Test
    void missingRule_producesFieldError() {
        SecurityEventRequest req = validRequest();
        req.setRule(null);
        List<IngestionError> errors = service.validate(req, 0);
        assertThat(fieldNames(errors)).contains("rule");
    }

    @Test
    void invalidAction_producesFieldError() {
        SecurityEventRequest req = validRequest();
        req.setAction("KICK");
        List<IngestionError> errors = service.validate(req, 0);
        assertThat(fieldNames(errors)).contains("action");
        assertThat(errors.stream()
                .filter(e -> "action".equals(e.getField()))
                .map(IngestionError::getMessage)
                .findFirst())
                .hasValueSatisfying(msg -> assertThat(msg).contains("DENY", "ALERT", "MONITOR"));
    }

    @Test
    void invalidRuleSeverity_producesFieldError() {
        SecurityEventRequest req = validRequest();
        req.getRule().setSeverity("EXTREME");
        List<IngestionError> errors = service.validate(req, 0);
        assertThat(fieldNames(errors)).contains("rule.severity");
    }

    @Test
    void invalidRuleCategory_producesFieldError() {
        SecurityEventRequest req = validRequest();
        req.getRule().setCategory("HACKING");
        List<IngestionError> errors = service.validate(req, 0);
        assertThat(fieldNames(errors)).contains("rule.category");
    }

    @Test
    void errorContainsCorrectEventIndex() {
        SecurityEventRequest req = validRequest();
        req.setClientIp(null);
        List<IngestionError> errors = service.validate(req, 3);
        assertThat(errors).allMatch(e -> e.getEventIndex() == 3);
    }
}
