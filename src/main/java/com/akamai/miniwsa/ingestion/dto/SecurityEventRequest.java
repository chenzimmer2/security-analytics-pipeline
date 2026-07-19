package com.akamai.miniwsa.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.Instant;

@Data
public class SecurityEventRequest {

    @NotBlank(message = "eventId is required")
    @JsonProperty("eventId")
    private String eventId;

    /**
     * The DLR spec calls this field "timestamp"; mapped to eventTimestamp
     * internally to distinguish it from server-assigned receivedAt.
     */
    @NotNull(message = "timestamp is required")
    @JsonProperty("timestamp")
    private Instant eventTimestamp;

    @NotNull(message = "configId is required")
    @JsonProperty("configId")
    private Long configId;

    @JsonProperty("policyId")
    private String policyId;

    @NotBlank(message = "clientIp is required")
    @JsonProperty("clientIp")
    private String clientIp;

    @JsonProperty("hostname")
    private String hostname;

    @JsonProperty("path")
    private String path;

    @JsonProperty("method")
    private String method;

    @JsonProperty("statusCode")
    private Integer statusCode;

    @JsonProperty("userAgent")
    private String userAgent;

    @NotNull(message = "rule is required")
    @Valid
    @JsonProperty("rule")
    private RuleDto rule;

    @NotBlank(message = "action is required")
    @Pattern(
        regexp = "DENY|ALERT|MONITOR",
        message = "action must be one of: DENY, ALERT, MONITOR"
    )
    @JsonProperty("action")
    private String action;

    @Valid
    @JsonProperty("geoLocation")
    private GeoLocationDto geoLocation;

    @JsonProperty("requestSize")
    private Integer requestSize;

    @JsonProperty("responseSize")
    private Integer responseSize;
}
