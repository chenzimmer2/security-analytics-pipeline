package com.akamai.miniwsa.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the event ingestion endpoint.
 *
 * Runs against a real PostgreSQL instance (Testcontainers) to avoid
 * dialect surprises with TIMESTAMPTZ, INTERVAL, and unique-constraint
 * behaviour that differ from H2.
 *
 * Kafka and Redis auto-configurations are excluded: neither is needed
 * for the ingestion pipeline under test.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
class EventIngestionControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("miniwsa_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final HttpHeaders JSON_HEADERS;
    static {
        JSON_HEADERS = new HttpHeaders();
        JSON_HEADERS.setContentType(MediaType.APPLICATION_JSON);
    }

    /** Returns a complete, schema-valid event JSON with the given eventId. */
    private String validEvent(String eventId) {
        return """
                {
                  "eventId": "%s",
                  "timestamp": "%s",
                  "configId": 14227,
                  "clientIp": "10.0.0.1",
                  "hostname": "www.example.com",
                  "path": "/api/v1/login",
                  "method": "POST",
                  "rule": {
                    "id": "950001",
                    "name": "SQL_INJECTION",
                    "message": "SQL Injection Detected",
                    "severity": "CRITICAL",
                    "category": "INJECTION"
                  },
                  "action": "DENY",
                  "geoLocation": { "country": "CN", "city": "Beijing" }
                }
                """.formatted(eventId, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(String body) {
        return rest.postForEntity(
                "/v1/events/ingest",
                new HttpEntity<>(body, JSON_HEADERS),
                Map.class);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void postSingleValidEvent_returns201_ingestedOne() {
        ResponseEntity<Map> response = post(validEvent(UUID.randomUUID().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("ingested", 1)
                                     .containsEntry("failed",   0);
    }

    @Test
    void postMissingClientIp_returns400_withFieldLevelError() {
        String body = """
                {
                  "eventId": "%s",
                  "timestamp": "%s",
                  "configId": 14227,
                  "rule": { "severity": "HIGH", "category": "BOT" },
                  "action": "DENY"
                }
                """.formatted(UUID.randomUUID(), Instant.now());

        ResponseEntity<Map> response = post(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("failed", 1);

        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.getBody().get("errors");
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0)).containsEntry("field", "clientIp");
    }

    @Test
    void postDuplicateEventId_returns400_withDuplicateMessage() {
        String eventId = UUID.randomUUID().toString();

        ResponseEntity<Map> first = post(validEvent(eventId));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second = post(validEvent(eventId));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getBody().get("message").toString())
                .containsIgnoringCase("duplicate");
    }

    /**
     * Verifies the atomicity contract: a batch containing even one invalid
     * event must be rejected entirely — the valid events must NOT be persisted.
     *
     * Proof: if the valid event ID can be ingested successfully in a follow-up
     * request (201, not 409), the first batch was fully rolled back.
     */
    @Test
    void postBatchWithOneInvalidEvent_returns400_validEventNotPersisted() {
        String validId   = UUID.randomUUID().toString();
        String invalidId = UUID.randomUUID().toString();  // will be missing clientIp

        String batchBody = """
                [
                  %s,
                  {
                    "eventId": "%s",
                    "timestamp": "%s",
                    "configId": 14227,
                    "rule": { "severity": "HIGH", "category": "BOT" },
                    "action": "DENY"
                  }
                ]
                """.formatted(validEvent(validId), invalidId, Instant.now());

        ResponseEntity<Map> batchResponse = post(batchBody);
        assertThat(batchResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // The valid event was NOT persisted — we can ingest it fresh now
        ResponseEntity<Map> retryResponse = post(validEvent(validId));
        assertThat(retryResponse.getStatusCode())
                .as("valid event should not have been saved in the failed batch")
                .isEqualTo(HttpStatus.CREATED);
    }

    /**
     * End-to-end smoke test: ingest known events → stats API reflects them.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ingestEvents_statsApiReflectsThemCorrectly() {
        String from = Instant.now().minusSeconds(60).toString();

        post(validEvent(UUID.randomUUID().toString()));  // INJECTION, DENY, CRITICAL
        post(validEvent(UUID.randomUUID().toString()));  // INJECTION, DENY, CRITICAL

        String to = Instant.now().plusSeconds(5).toString();

        ResponseEntity<Map> statsResponse = rest.getForEntity(
                "/v1/stats/summary?from={from}&to={to}",
                Map.class,
                from, to);

        assertThat(statsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = statsResponse.getBody();
        assertThat((Integer) body.get("totalEvents")).isGreaterThanOrEqualTo(2);

        Map<String, Map<String, Object>> byCategory =
                (Map<String, Map<String, Object>>) body.get("byCategory");
        assertThat(byCategory).containsKey("INJECTION");
        assertThat((Integer) byCategory.get("INJECTION").get("count")).isGreaterThanOrEqualTo(2);

        Map<String, Integer> byAction = (Map<String, Integer>) body.get("byAction");
        assertThat(byAction).containsKey("DENY");
        assertThat(byAction.get("DENY")).isGreaterThanOrEqualTo(2);
    }
}
