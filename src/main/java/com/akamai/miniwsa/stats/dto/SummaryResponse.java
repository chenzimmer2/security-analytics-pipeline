package com.akamai.miniwsa.stats.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Value
@Builder
public class SummaryResponse {
    Long configId;
    TimeRange timeRange;
    long totalEvents;
    Map<String, CategoryStats> byCategory;
    Map<String, Long> byAction;
    List<AttackerStats> topAttackers;
    List<PathStats> topTargetedPaths;

    @Value
    @Builder
    public static class TimeRange {
        Instant from;
        Instant to;
    }
}
