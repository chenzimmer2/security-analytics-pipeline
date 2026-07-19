package com.akamai.miniwsa.ingestion.mapper;

import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Maps a validated SecurityEventRequest DTO to a SecurityEvent entity.
 * receivedAt is always assigned here (server-side) — never sourced from the request.
 */
@Component
public class SecurityEventMapper {

    public SecurityEvent toEntity(SecurityEventRequest req) {
        SecurityEvent.SecurityEventBuilder builder = SecurityEvent.builder()
                .eventId(req.getEventId())
                .eventTimestamp(req.getEventTimestamp())
                .receivedAt(Instant.now())
                .configId(req.getConfigId())
                .policyId(req.getPolicyId())
                .clientIp(req.getClientIp())
                .hostname(req.getHostname())
                .path(req.getPath())
                .method(req.getMethod())
                .statusCode(req.getStatusCode())
                .userAgent(req.getUserAgent())
                .action(req.getAction())
                .requestSize(req.getRequestSize())
                .responseSize(req.getResponseSize());

        if (req.getRule() != null) {
            builder.ruleId(req.getRule().getId())
                   .ruleName(req.getRule().getName())
                   .ruleMessage(req.getRule().getMessage())
                   .ruleSeverity(req.getRule().getSeverity())
                   .ruleCategory(req.getRule().getCategory());
        }

        if (req.getGeoLocation() != null) {
            builder.geoCountry(req.getGeoLocation().getCountry())
                   .geoCity(req.getGeoLocation().getCity());
        }

        return builder.build();
    }
}
