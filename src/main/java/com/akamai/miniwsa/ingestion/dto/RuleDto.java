package com.akamai.miniwsa.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class RuleDto {

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("message")
    private String message;

    @NotBlank(message = "rule.severity is required")
    @Pattern(
        regexp = "CRITICAL|HIGH|MEDIUM|LOW",
        message = "rule.severity must be one of: CRITICAL, HIGH, MEDIUM, LOW"
    )
    @JsonProperty("severity")
    private String severity;

    @NotBlank(message = "rule.category is required")
    @Pattern(
        regexp = "INJECTION|XSS|PROTOCOL_VIOLATION|DATA_LEAKAGE|BOT|DOS|RATE_LIMIT",
        message = "rule.category must be one of: INJECTION, XSS, PROTOCOL_VIOLATION, DATA_LEAKAGE, BOT, DOS, RATE_LIMIT"
    )
    @JsonProperty("category")
    private String category;
}
