package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.enrichment.service.RepeatOffenderDetectionService;
import com.akamai.miniwsa.enrichment.service.ThreatScoringService;
import com.akamai.miniwsa.ingestion.dto.RuleDto;
import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThreatScoringServiceTest {

    @Mock
    private RepeatOffenderDetectionService repeatOffenderDetectionService;

    @InjectMocks
    private ThreatScoringService service;

    private SecurityEventRequest buildRequest(String severity, String action, String path) {
        RuleDto rule = new RuleDto();
        rule.setSeverity(severity);
        rule.setCategory("INJECTION");

        SecurityEventRequest req = new SecurityEventRequest();
        req.setEventId("evt-001");
        req.setEventTimestamp(Instant.now());
        req.setConfigId(1L);
        req.setClientIp("1.2.3.4");
        req.setPath(path);
        req.setMethod("GET");
        req.setStatusCode(200);
        req.setRule(rule);
        req.setAction(action);
        return req;
    }

    @Test
    void criticalDenyAdminPath_nonRepeat_scores75() {
        when(repeatOffenderDetectionService.isRepeatOffender(anyString())).thenReturn(false);
        // CRITICAL(40) + DENY(20) + /admin(15) = 75
        int score = service.computeScore(buildRequest("CRITICAL", "DENY", "/admin/users"));
        assertThat(score).isEqualTo(75);
    }

    @Test
    void repeatOffenderAddsBonus() {
        when(repeatOffenderDetectionService.isRepeatOffender(anyString())).thenReturn(true);
        // CRITICAL(40) + DENY(20) + /admin(15) + repeat(15) = 90
        int score = service.computeScore(buildRequest("CRITICAL", "DENY", "/admin/users"));
        assertThat(score).isEqualTo(90);
    }

    @Test
    void scoreIsCappedAt100() {
        when(repeatOffenderDetectionService.isRepeatOffender(anyString())).thenReturn(true);
        int score = service.computeScore(buildRequest("CRITICAL", "DENY", "/login"));
        assertThat(score).isLessThanOrEqualTo(100);
    }

    @Test
    void adminPathAddsScore() {
        when(repeatOffenderDetectionService.isRepeatOffender(anyString())).thenReturn(false);
        // LOW(10) + MONITOR(0) + /admin(15) = 25
        int score = service.computeScore(buildRequest("LOW", "MONITOR", "/admin/dashboard"));
        assertThat(score).isEqualTo(25);
    }

    @Test
    void loginPathAddsScore() {
        when(repeatOffenderDetectionService.isRepeatOffender(anyString())).thenReturn(false);
        // LOW(10) + MONITOR(0) + /login(15) = 25
        int score = service.computeScore(buildRequest("LOW", "MONITOR", "/api/v1/login"));
        assertThat(score).isEqualTo(25);
    }

    @Test
    void neutralPathAddsNoScore() {
        when(repeatOffenderDetectionService.isRepeatOffender(anyString())).thenReturn(false);
        // LOW(10) + MONITOR(0) + /public(0) = 10
        int score = service.computeScore(buildRequest("LOW", "MONITOR", "/api/v1/products"));
        assertThat(score).isEqualTo(10);
    }

    @Test
    void highSeverityScores30() {
        when(repeatOffenderDetectionService.isRepeatOffender(anyString())).thenReturn(false);
        // HIGH(30) + MONITOR(0) + /public(0) = 30
        int score = service.computeScore(buildRequest("HIGH", "MONITOR", "/api/v1/products"));
        assertThat(score).isEqualTo(30);
    }

    @Test
    void mediumSeverityScores20() {
        when(repeatOffenderDetectionService.isRepeatOffender(anyString())).thenReturn(false);
        // MEDIUM(20) + MONITOR(0) + /public(0) = 20
        int score = service.computeScore(buildRequest("MEDIUM", "MONITOR", "/api/v1/products"));
        assertThat(score).isEqualTo(20);
    }

    @Test
    void alertActionScores10() {
        when(repeatOffenderDetectionService.isRepeatOffender(anyString())).thenReturn(false);
        // LOW(10) + ALERT(10) + /public(0) = 20
        int score = service.computeScore(buildRequest("LOW", "ALERT", "/api/v1/products"));
        assertThat(score).isEqualTo(20);
    }

    @Test
    void nullPathAddsNoScore() {
        when(repeatOffenderDetectionService.isRepeatOffender(anyString())).thenReturn(false);
        // LOW(10) + MONITOR(0) + null(0) = 10
        int score = service.computeScore(buildRequest("LOW", "MONITOR", null));
        assertThat(score).isEqualTo(10);
    }

    @Test
    void nullRuleAndMonitorAction_noRepeat_scores0() {
        when(repeatOffenderDetectionService.isRepeatOffender(anyString())).thenReturn(false);
        SecurityEventRequest req = new SecurityEventRequest();
        req.setEventId("evt-002");
        req.setEventTimestamp(Instant.now());
        req.setConfigId(1L);
        req.setClientIp("1.2.3.4");
        req.setPath("/public");
        req.setAction("MONITOR");
        int score = service.computeScore(req);
        assertThat(score).isEqualTo(0);
    }
}
