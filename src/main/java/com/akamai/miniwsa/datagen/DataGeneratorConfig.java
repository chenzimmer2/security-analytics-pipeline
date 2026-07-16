package com.akamai.miniwsa.datagen;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized configuration for the data generator.
 * All properties are optional — sensible defaults produce a 10 k-event run
 * against a locally running app instance.
 */
@Data
@Component
@ConfigurationProperties(prefix = "datagen")
public class DataGeneratorConfig {

    /** Total number of events to generate and ingest. */
    private int count = 10_000;

    /** Events submitted per POST /v1/events/ingest call. */
    private int batchSize = 100;

    /** Base URL of the target Mini-WSA instance. */
    private String targetUrl = "http://localhost:8080";

    /**
     * Number of "attacker" IPs from the fixed pool that will receive the
     * repeat-offender seed treatment before the main generation phase.
     */
    private int attackerIpCount = 3;

    /**
     * Events sent per attacker IP in Phase 1 (seed phase).
     * Must be > 5 so subsequent events from the same IP cross the
     * isRepeatOffender threshold (count > 5 within last 10 minutes).
     */
    private int waveSeedSize = 7;
}
