package com.akamai.miniwsa.stats.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PathStats {
    String path;
    long count;
}
