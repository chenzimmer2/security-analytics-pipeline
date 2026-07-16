package com.akamai.miniwsa.samples.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class SamplesResponse {
    long total;
    List<SecurityEventResponse> data;
}
