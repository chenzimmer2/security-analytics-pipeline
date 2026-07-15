package com.akamai.miniwsa.stats.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AttackerStats {
    String clientIp;
    long count;
    double avgThreatScore;
}
