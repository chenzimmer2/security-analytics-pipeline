package com.akamai.miniwsa.ingestion.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class IngestionResponse {
    int ingested;
    int failed;
    List<IngestionError> errors;
}
