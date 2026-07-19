package com.akamai.miniwsa.enrichment.service;

import com.akamai.miniwsa.repository.SecurityEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Detects repeat offenders via a DB COUNT query on received_at.
 * Uses server-assigned received_at (not client-provided event_timestamp)
 * to prevent timestamp manipulation.
 */
@Service
@RequiredArgsConstructor
public class RepeatOffenderDetectionService {

    private static final int REPEAT_THRESHOLD = 5;
    private static final int WINDOW_MINUTES   = 10;

    private final SecurityEventRepository repository;

    /**
     * Returns true if the given IP has sent more than {@value REPEAT_THRESHOLD} events
     * in the last {@value WINDOW_MINUTES} minutes (measured by received_at).
     */
    public boolean isRepeatOffender(String clientIp) {
        Instant since = Instant.now().minus(WINDOW_MINUTES, ChronoUnit.MINUTES);
        long count = repository.countByClientIpAndReceivedAtAfter(clientIp, since);
        return count > REPEAT_THRESHOLD;
    }
}
