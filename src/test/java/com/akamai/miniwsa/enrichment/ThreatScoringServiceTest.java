package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.enrichment.service.ThreatScoringService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests — no Spring context, no mocks.
 *
 * ThreatScoringService no longer holds a repository dependency; repeat-offender
 * status is passed in as a pre-built Map so the test controls it directly
 * without any stubbing.
 */
class ThreatScoringServiceTest {

    private final ThreatScoringService service = new ThreatScoringService();

    private SecurityEvent buildEvent(String severity, String action, String path) {
        return SecurityEvent.builder()
                .eventId("evt-001")
                .eventTimestamp(Instant.now())
                .receivedAt(Instant.now())
                .configId(1L)
                .clientIp("1.2.3.4")
                .path(path)
                .method("GET")
                .statusCode(200)
                .ruleSeverity(severity)
                .ruleCategory("INJECTION")
                .action(action)
                .build();
    }

    /** Convenience: non-repeat-offender call. */
    private int score(String severity, String action, String path) {
        return service.computeScore(buildEvent(severity, action, path), Map.of());
    }

    /** Convenience: repeat-offender call for clientIp "1.2.3.4". */
    private int scoreRepeat(String severity, String action, String path) {
        return service.computeScore(buildEvent(severity, action, path),
                Map.of("1.2.3.4", true));
    }

    // ── Severity ──────────────────────────────────────────────────────────────

    @Test
    void criticalSeverityScores40() {
        assertThat(score("CRITICAL", "MONITOR", "/other")).isEqualTo(40);
    }

    @Test
    void highSeverityScores30() {
        assertThat(score("HIGH", "MONITOR", "/other")).isEqualTo(30);
    }

    @Test
    void mediumSeverityScores20() {
        assertThat(score("MEDIUM", "MONITOR", "/other")).isEqualTo(20);
    }

    @Test
    void lowSeverityScores10() {
        assertThat(score("LOW", "MONITOR", "/other")).isEqualTo(10);
    }

    // ── Action ────────────────────────────────────────────────────────────────

    @Test
    void denyActionScores20() {
        assertThat(score("LOW", "DENY", "/other")).isEqualTo(30);  // LOW(10)+DENY(20)
    }

    @Test
    void alertActionScores10() {
        assertThat(score("LOW", "ALERT", "/other")).isEqualTo(20); // LOW(10)+ALERT(10)
    }

    @Test
    void monitorActionScores0() {
        assertThat(score("LOW", "MONITOR", "/other")).isEqualTo(10); // LOW(10)+MONITOR(0)
    }

    // ── Path ──────────────────────────────────────────────────────────────────

    @Test
    void adminPathAddsScore() {
        assertThat(score("LOW", "MONITOR", "/admin/dashboard")).isEqualTo(25); // LOW(10)+admin(15)
    }

    @Test
    void loginPathAddsScore() {
        assertThat(score("LOW", "MONITOR", "/api/v1/login")).isEqualTo(25); // LOW(10)+login(15)
    }

    @Test
    void neutralPathAddsNoScore() {
        assertThat(score("LOW", "MONITOR", "/api/v1/products")).isEqualTo(10);
    }

    @Test
    void nullPathAddsNoScore() {
        assertThat(score("LOW", "MONITOR", null)).isEqualTo(10);
    }

    // ── Repeat-offender bonus ─────────────────────────────────────────────────

    @Test
    void repeatOffenderAddsBonus() {
        // CRITICAL(40) + DENY(20) + /admin(15) + repeat(15) = 90
        assertThat(scoreRepeat("CRITICAL", "DENY", "/admin/users")).isEqualTo(90);
    }

    @Test
    void nonRepeatOffenderNoBonus() {
        // CRITICAL(40) + DENY(20) + /admin(15) = 75
        assertThat(score("CRITICAL", "DENY", "/admin/users")).isEqualTo(75);
    }

    // ── Combinations ──────────────────────────────────────────────────────────

    @Test
    void criticalDenyAdminPath_nonRepeat_scores75() {
        assertThat(score("CRITICAL", "DENY", "/admin/users")).isEqualTo(75);
    }

    @Test
    void criticalDenyLoginRepeat_maxPossibleScore_is90() {
        // CRITICAL(40) + DENY(20) + /login(15) + repeat(15) = 90.
        // This is the maximum achievable with the current scoring table.
        // Math.min(score, 100) kicks in only if the table is extended to produce > 100.
        assertThat(scoreRepeat("CRITICAL", "DENY", "/api/v1/login")).isEqualTo(90);
    }

    // ── Null / edge cases ─────────────────────────────────────────────────────

    @Test
    void nullRuleAndMonitorAction_noRepeat_scores0() {
        SecurityEvent event = SecurityEvent.builder()
                .eventId("evt-002")
                .configId(1L)
                .clientIp("1.2.3.4")
                .path("/public")
                .action("MONITOR")
                .build();
        assertThat(service.computeScore(event, Map.of())).isEqualTo(0);
    }

    @Test
    void unknownIpNotInCacheCountsAsNonRepeat() {
        // Cache is for a different IP — the event's IP should default to false.
        SecurityEvent event = buildEvent("CRITICAL", "DENY", "/admin");
        Map<String, Boolean> cache = Map.of("9.9.9.9", true); // different IP
        assertThat(service.computeScore(event, cache)).isEqualTo(75); // no repeat bonus
    }
}
