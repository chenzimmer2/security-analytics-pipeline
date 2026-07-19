package com.akamai.miniwsa.enrichment.service;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ClassificationService {

    private static final Map<String, String> CATEGORY_MAP = Map.of(
            "INJECTION",          "SQL/Command Injection",
            "XSS",                "Cross-Site Scripting",
            "PROTOCOL_VIOLATION", "Protocol Anomaly",
            "DATA_LEAKAGE",       "Data Exfiltration",
            "BOT",                "Bot Activity",
            "DOS",                "Denial of Service",
            "RATE_LIMIT",         "Rate Limiting"
    );

    public String classify(String ruleCategory) {
        if (ruleCategory == null) {
            return "Unknown";
        }
        return CATEGORY_MAP.getOrDefault(ruleCategory.toUpperCase(), "Unknown");
    }
}
