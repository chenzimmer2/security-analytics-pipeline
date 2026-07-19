package com.akamai.miniwsa.samples.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class SecurityEventResponse {
    String  eventId;
    Instant eventTimestamp;
    Instant receivedAt;
    Long    configId;
    String  policyId;
    String  clientIp;
    String  hostname;
    String  path;
    String  method;
    Integer statusCode;
    String  userAgent;
    String  ruleId;
    String  ruleName;
    String  ruleSeverity;
    String  ruleCategory;
    String  action;
    String  geoCountry;
    String  geoCity;
    Integer requestSize;
    Integer responseSize;
    String  attackType;
    Integer threatScore;
}
