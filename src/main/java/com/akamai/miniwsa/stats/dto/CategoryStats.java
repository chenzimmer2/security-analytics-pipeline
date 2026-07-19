package com.akamai.miniwsa.stats.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CategoryStats {
    long count;
    double avgThreatScore;
}
