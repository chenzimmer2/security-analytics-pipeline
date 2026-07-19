package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.enrichment.service.RepeatOffenderDetectionService;
import com.akamai.miniwsa.repository.SecurityEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The repeat-offender threshold is: count > 5 (strictly greater than).
 * Tests verify the boundary at 5 (false) and 6 (true).
 */
@ExtendWith(MockitoExtension.class)
class RepeatOffenderDetectionServiceTest {

    @Mock
    private SecurityEventRepository repository;

    @InjectMocks
    private RepeatOffenderDetectionService service;

    @Test
    void zeroEvents_notARepeatOffender() {
        when(repository.countByClientIpAndReceivedAtAfter(eq("1.2.3.4"), any(Instant.class)))
                .thenReturn(0L);
        assertThat(service.isRepeatOffender("1.2.3.4")).isFalse();
    }

    @Test
    void fiveEvents_exactlyAtThreshold_notARepeatOffender() {
        // threshold is >5, so 5 is still not a repeat offender
        when(repository.countByClientIpAndReceivedAtAfter(eq("1.2.3.4"), any(Instant.class)))
                .thenReturn(5L);
        assertThat(service.isRepeatOffender("1.2.3.4")).isFalse();
    }

    @Test
    void sixEvents_oneAboveThreshold_isRepeatOffender() {
        when(repository.countByClientIpAndReceivedAtAfter(eq("1.2.3.4"), any(Instant.class)))
                .thenReturn(6L);
        assertThat(service.isRepeatOffender("1.2.3.4")).isTrue();
    }

    @Test
    void manyEvents_isRepeatOffender() {
        when(repository.countByClientIpAndReceivedAtAfter(eq("5.5.5.5"), any(Instant.class)))
                .thenReturn(500L);
        assertThat(service.isRepeatOffender("5.5.5.5")).isTrue();
    }
}
