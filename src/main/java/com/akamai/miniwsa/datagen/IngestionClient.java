package com.akamai.miniwsa.datagen;

import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client that posts batches of events to the live ingestion endpoint.
 *
 * {@link RestTemplate} is built via {@link RestTemplateBuilder} so that
 * connect/read timeouts are enforced and interceptors (e.g. for auth headers
 * or request logging) can be added centrally without touching call sites.
 *
 * Error handling strategy: log and continue.  A failed batch does not abort
 * the generator run — partial data is still useful for testing analytics.
 */
@Slf4j
@Component
@Profile("datagen")
public class IngestionClient {

    private static final HttpHeaders JSON_HEADERS;

    static {
        JSON_HEADERS = new HttpHeaders();
        JSON_HEADERS.setContentType(MediaType.APPLICATION_JSON);
    }

    private final String ingestUrl;
    private final RestTemplate restTemplate;

    public IngestionClient(DataGeneratorConfig config, RestTemplateBuilder builder) {
        this.ingestUrl = config.getTargetUrl() + "/v1/events/ingest";
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * POSTs {@code batch} to the ingestion endpoint.
     *
     * @return the number of events the server reported as successfully ingested
     */
    @SuppressWarnings("unchecked")
    public int postBatch(List<SecurityEventRequest> batch) {
        HttpEntity<List<SecurityEventRequest>> entity = new HttpEntity<>(batch, JSON_HEADERS);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    ingestUrl, HttpMethod.POST, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Number ingested = (Number) response.getBody().get("ingested");
                int failed = numberOrZero(response.getBody().get("failed"));
                if (failed > 0) {
                    log.warn("[DataGen] Batch partially rejected — ingested={}, failed={}", ingested, failed);
                }
                return ingested != null ? ingested.intValue() : 0;
            }

            log.warn("[DataGen] Unexpected HTTP {} for batch of {} events",
                    response.getStatusCode(), batch.size());
            return 0;

        } catch (HttpClientErrorException e) {
            log.error("[DataGen] Client error {} posting batch of {} — {}",
                    e.getStatusCode(), batch.size(), e.getResponseBodyAsString());
            return 0;
        } catch (HttpServerErrorException e) {
            log.error("[DataGen] Server error {} posting batch of {} — {}",
                    e.getStatusCode(), batch.size(), e.getMessage());
            return 0;
        } catch (Exception e) {
            log.error("[DataGen] Unexpected error posting batch of {}: {}", batch.size(), e.getMessage());
            return 0;
        }
    }

    private static int numberOrZero(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
