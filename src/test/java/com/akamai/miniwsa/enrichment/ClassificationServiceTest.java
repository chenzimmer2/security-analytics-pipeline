package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.enrichment.service.ClassificationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassificationServiceTest {

    private final ClassificationService service = new ClassificationService();

    @Test
    void knownCategoryMapsCorrectly() {
        assertThat(service.classify("INJECTION")).isEqualTo("SQL/Command Injection");
        assertThat(service.classify("XSS")).isEqualTo("Cross-Site Scripting");
        assertThat(service.classify("BOT")).isEqualTo("Bot Activity");
    }

    @Test
    void caseInsensitiveMapping() {
        assertThat(service.classify("injection")).isEqualTo("SQL/Command Injection");
    }

    @Test
    void unknownCategoryReturnsUnknown() {
        assertThat(service.classify("SOME_FUTURE_CATEGORY")).isEqualTo("Unknown");
        assertThat(service.classify(null)).isEqualTo("Unknown");
    }
}
