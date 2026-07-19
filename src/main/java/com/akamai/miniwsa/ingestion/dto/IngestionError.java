package com.akamai.miniwsa.ingestion.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngestionError {
    private int    eventIndex;
    private String field;
    private String message;
}
