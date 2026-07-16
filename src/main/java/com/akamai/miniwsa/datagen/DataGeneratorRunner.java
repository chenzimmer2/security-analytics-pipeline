package com.akamai.miniwsa.datagen;

import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Entry point for the data-generation run.
 *
 * Activated exclusively under the {@code datagen} Spring profile so that it
 * never fires in the production or test contexts.
 *
 * <h2>Two-phase strategy</h2>
 *
 * <p><b>Phase 1 — Seed.</b>  The repeat-offender check in
 * {@code RepeatOffenderDetectionService} queries the database <em>before</em>
 * the current batch is committed.  To guarantee that attacker events receive
 * the +15 bonus, we first POST a small seed batch (≥ 7 events) for each
 * designated attacker IP.  Once those events are persisted, every subsequent
 * event from the same IP within a 10-minute window will satisfy
 * {@code count > 5} and receive the bonus.
 *
 * <p><b>Phase 2 — Main loop.</b>  Generates {@code datagen.count} events:
 * ~30 % from attacker IPs (uniformly spread across the attacker pool) and
 * ~70 % from normal IPs.  All events are shuffled before being split into
 * batches and POSTed, producing a realistic interleaved time-series.
 *
 * <h2>Running</h2>
 * <pre>
 * # Terminal 1 — start the main app
 * ./mvnw spring-boot:run
 *
 * # Terminal 2 — run the generator (connects to the same PostgreSQL,
 * #              POSTs to localhost:8080, exits when done)
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=datagen
 *
 * # Override event count:
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=datagen \
 *        -Dspring-boot.run.jvmArguments="-Ddatagen.count=50000"
 * </pre>
 */
@Slf4j
@Component
@Profile("datagen")
@RequiredArgsConstructor
public class DataGeneratorRunner implements ApplicationRunner {

    /**
     * Fixed attacker IP pool.  The first {@code datagen.attacker-ip-count}
     * entries are used; the rest serve as normal-traffic IPs.
     */
    private static final List<String> IP_POOL = List.of(
            // Designated attacker IPs (indices 0-5)
            "203.0.113.42",
            "198.51.100.17",
            "185.234.219.4",
            "91.108.4.0",
            "176.31.208.10",
            "5.255.255.80",
            // Normal background IPs (indices 6-19)
            "172.16.0.1",
            "10.0.0.50",
            "192.168.1.100",
            "45.33.32.156",
            "74.125.224.72",
            "142.250.72.46",
            "52.84.239.116",
            "104.196.183.231",
            "34.86.59.84",
            "216.58.217.78",
            "151.101.193.140",
            "99.84.187.30",
            "52.72.169.180",
            "13.227.220.130"
    );

    private final DataGeneratorConfig config;
    private final EventFactory        eventFactory;
    private final IngestionClient     ingestionClient;

    @Override
    public void run(ApplicationArguments args) {
        long startMs = System.currentTimeMillis();

        int attackerCount = Math.min(config.getAttackerIpCount(), IP_POOL.size());
        List<String> attackerIps = IP_POOL.subList(0, attackerCount);
        List<String> normalIps   = IP_POOL.subList(attackerCount, IP_POOL.size());

        log.info("[DataGen] ════════════════════════════════════════════");
        log.info("[DataGen] Starting: {} events  •  batch size: {}  •  target: {}",
                config.getCount(), config.getBatchSize(), config.getTargetUrl());
        log.info("[DataGen] Attacker IPs ({}) : {}", attackerIps.size(), attackerIps);
        log.info("[DataGen] Normal IPs   ({}) : {} (pool)", normalIps.size(), normalIps.size());

        // ── Phase 1: seed attacker IPs ────────────────────────────────────────
        log.info("[DataGen] ── Phase 1: seeding {} attacker IPs ({} events each) ──",
                attackerIps.size(), config.getWaveSeedSize());

        int seedTotal = 0;
        for (String attackerIp : attackerIps) {
            List<SecurityEventRequest> seedBatch = buildBatch(attackerIp, config.getWaveSeedSize());
            seedTotal += ingestionClient.postBatch(seedBatch);
        }
        log.info("[DataGen] Phase 1 complete — {} seed events ingested", seedTotal);

        // ── Phase 2: main event generation ───────────────────────────────────
        log.info("[DataGen] ── Phase 2: generating {} main events ──", config.getCount());

        int attackerShare = (int) (config.getCount() * 0.30);
        int normalShare   = config.getCount() - attackerShare;
        int perAttacker   = attackerShare / attackerCount;

        List<SecurityEventRequest> allEvents = new ArrayList<>(config.getCount());

        // Attacker events — distributed across the attacker pool
        for (String attackerIp : attackerIps) {
            for (int i = 0; i < perAttacker; i++) {
                allEvents.add(eventFactory.build(attackerIp));
            }
        }
        // Absorb any remainder into the first attacker IP
        int remainder = attackerShare - (perAttacker * attackerCount);
        for (int i = 0; i < remainder; i++) {
            allEvents.add(eventFactory.build(attackerIps.get(0)));
        }

        // Normal events — pick randomly from the normal IP pool
        for (int i = 0; i < normalShare; i++) {
            String ip = normalIps.get(ThreadLocalRandom.current().nextInt(normalIps.size()));
            allEvents.add(eventFactory.build(ip));
        }

        // Shuffle so attacker events are not clustered at the start of batches
        Collections.shuffle(allEvents);

        // Post in configurable batches
        int totalIngested  = 0;
        int batchNumber    = 0;
        int totalBatches   = (int) Math.ceil((double) allEvents.size() / config.getBatchSize());
        int reportInterval = Math.max(1, totalBatches / 10);  // log ~10 progress lines

        for (int i = 0; i < allEvents.size(); i += config.getBatchSize()) {
            int end   = Math.min(i + config.getBatchSize(), allEvents.size());
            List<SecurityEventRequest> batch = allEvents.subList(i, end);

            totalIngested += ingestionClient.postBatch(batch);
            batchNumber++;

            if (batchNumber % reportInterval == 0 || batchNumber == totalBatches) {
                int pct = (int) ((long) totalIngested * 100 / config.getCount());
                log.info("[DataGen] Progress: {}/{} events ingested ({}%)",
                        totalIngested, config.getCount(), pct);
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("[DataGen] ════════════════════════════════════════════");
        log.info("[DataGen] Complete — {} events ingested in {}s",
                totalIngested + seedTotal, String.format("%.1f", elapsedMs / 1000.0));
        log.info("[DataGen] Seed events : {}", seedTotal);
        log.info("[DataGen] Main events : {} (attacker {}  •  normal {})",
                totalIngested, attackerShare, normalShare);
        log.info("[DataGen] Verify with:");
        log.info("[DataGen]   curl -s 'http://localhost:8080/v1/stats/summary?from={}Z&to={}Z' | jq .",
                java.time.Instant.now().minusSeconds(48 * 3600).toString().replace("Z", ""),
                java.time.Instant.now().toString().replace("Z", ""));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<SecurityEventRequest> buildBatch(String ip, int size) {
        List<SecurityEventRequest> batch = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            batch.add(eventFactory.build(ip));
        }
        return batch;
    }
}
