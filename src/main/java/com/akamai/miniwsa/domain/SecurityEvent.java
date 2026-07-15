package com.akamai.miniwsa.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "security_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 100)
    private String eventId;

    /** Client-provided event time — used for analytics queries. */
    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    /** Server-assigned ingestion time — used for repeat-offender detection. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "config_id", nullable = false)
    private Long configId;

    @Column(name = "policy_id", length = 100)
    private String policyId;

    @Column(name = "client_ip", nullable = false, length = 45)
    private String clientIp;

    @Column(name = "hostname", length = 255)
    private String hostname;

    @Column(name = "path")
    private String path;

    @Column(name = "method", length = 10)
    private String method;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "rule_id", length = 50)
    private String ruleId;

    @Column(name = "rule_name", length = 100)
    private String ruleName;

    @Column(name = "rule_message")
    private String ruleMessage;

    @Column(name = "rule_severity", length = 20)
    private String ruleSeverity;

    @Column(name = "rule_category", length = 50)
    private String ruleCategory;

    @Column(name = "action", length = 20)
    private String action;

    @Column(name = "geo_country", length = 10)
    private String geoCountry;

    @Column(name = "geo_city", length = 100)
    private String geoCity;

    @Column(name = "request_size")
    private Integer requestSize;

    @Column(name = "response_size")
    private Integer responseSize;

    /** Derived by ClassificationService from rule_category. */
    @Column(name = "attack_type", length = 100)
    private String attackType;

    /** Computed 0-100 by ThreatScoringService. */
    @Column(name = "threat_score")
    private Integer threatScore;
}
