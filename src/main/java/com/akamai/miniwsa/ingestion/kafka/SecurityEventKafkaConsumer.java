package com.akamai.miniwsa.ingestion.kafka;

import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import com.akamai.miniwsa.ingestion.dto.IngestionResponse;
import com.akamai.miniwsa.ingestion.service.IngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Consumes security events from the {@code security-events} Kafka topic and
 * feeds them through the same ingestion pipeline as the REST endpoint.
 *
 * <p>One message = one event (single-object JSON, same schema as the REST body).
 *
 * <p>Error handling strategy — log and skip, never re-queue:
 * <ul>
 *   <li>Malformed JSON → logged as error, offset committed (poison-pill avoidance).</li>
 *   <li>Validation failure → logged as warning, offset committed.</li>
 *   <li>Duplicate event_id → logged as debug (idempotent re-delivery is expected), offset committed.</li>
 *   <li>Unexpected error → logged as error, offset committed.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityEventKafkaConsumer {

    private final IngestionService ingestionService;
    private final ObjectMapper     objectMapper;

    @KafkaListener(topics = "security-events", groupId = "mini-wsa-group")
    public void consume(String message) {
        SecurityEventRequest request;
        try {
            request = objectMapper.readValue(message, SecurityEventRequest.class);
        } catch (Exception e) {
            log.error("[Kafka] Malformed message — skipping. Error: {}. Payload: {}",
                    e.getMessage(), truncate(message));
            return;
        }

        try {
            IngestionResponse response = ingestionService.ingestAll(List.of(request));
            if (response.getFailed() > 0) {
                log.warn("[Kafka] Event rejected by validation — eventId={}, errors={}",
                        request.getEventId(), response.getErrors());
            } else {
                log.debug("[Kafka] Ingested eventId={}", request.getEventId());
            }
        } catch (DataIntegrityViolationException e) {
            log.debug("[Kafka] Duplicate eventId={} — skipping (idempotent re-delivery)",
                    request.getEventId());
        } catch (Exception e) {
            log.error("[Kafka] Unexpected error ingesting eventId={}: {}",
                    request.getEventId(), e.getMessage());
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
