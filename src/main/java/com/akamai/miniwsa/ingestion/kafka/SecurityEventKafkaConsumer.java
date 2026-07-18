package com.akamai.miniwsa.ingestion.kafka;

import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import com.akamai.miniwsa.ingestion.service.IngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Consumes security events from the {@code security-events} Kafka topic.
 *
 * <p>Runs in batch mode ({@code spring.kafka.listener.type=batch}).  One poll
 * cycle delivers a {@code List<String>} of raw JSON payloads.  The consumer
 * deserialises every message in the list, discards malformed payloads (poison-
 * pill avoidance), and hands the valid requests to
 * {@link IngestionService#processKafkaBatch} which:
 * <ul>
 *   <li>resolves repeat-offender status with one DB query per unique IP across
 *       the entire poll batch (not one per event), and</li>
 *   <li>saves each valid event in its own transaction so a single failure never
 *       blocks the rest of the batch.</li>
 * </ul>
 *
 * <p>Error handling strategy — log and skip, never re-queue:
 * <ul>
 *   <li>Malformed JSON → logged as error, offset committed.</li>
 *   <li>Validation failure → logged as warning inside processKafkaBatch.</li>
 *   <li>Duplicate event_id → logged as debug inside processKafkaBatch.</li>
 *   <li>Unexpected save error → logged as error inside processKafkaBatch.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityEventKafkaConsumer {

    private final IngestionService ingestionService;
    private final ObjectMapper     objectMapper;

    @KafkaListener(topics = "security-events", groupId = "mini-wsa-group")
    public void consume(List<String> messages) {
        List<SecurityEventRequest> requests = new ArrayList<>(messages.size());

        for (String message : messages) {
            try {
                requests.add(objectMapper.readValue(message, SecurityEventRequest.class));
            } catch (Exception e) {
                log.error("[Kafka] Malformed message — skipping. Error: {}. Payload: {}",
                        e.getMessage(), truncate(message));
            }
        }

        if (!requests.isEmpty()) {
            log.debug("[Kafka] Processing poll batch of {} event(s)", requests.size());
            ingestionService.processKafkaBatch(requests);
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
