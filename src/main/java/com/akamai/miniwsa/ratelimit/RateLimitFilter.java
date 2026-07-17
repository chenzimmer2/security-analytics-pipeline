package com.akamai.miniwsa.ratelimit;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Servlet filter that enforces per-IP rate limits using Redis INCR + EXPIRE.
 *
 * Redis INCR + EXPIRE per key: count==1 starts a 60s window; over limit → 429.
 * Tiered: ingest 1000 req/min, stats/samples 100 req/min.
 * Client IP resolved from X-Forwarded-For, falling back to remoteAddr.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements Filter {

    private static final int    WINDOW_SECONDS = 60;
    private static final int    READ_LIMIT     = 100;
    private static final int    INGEST_LIMIT   = 1000;

    private final StringRedisTemplate redisTemplate;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req = (HttpServletRequest)  request;
        HttpServletResponse res = (HttpServletResponse) response;

        Optional<Tier> tier = resolveTier(req);
        if (tier.isEmpty()) {
            // Unrated path — pass through
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(req);
        String key      = "rate_limit:" + tier.get().keyPrefix + ":" + clientIp;
        int    limit    = tier.get().limit;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            // Redis unavailable — fail open (don't block traffic)
            log.warn("[RateLimit] Redis returned null for key {}; failing open", key);
            chain.doFilter(request, response);
            return;
        }

        if (count == 1) {
            // First request in this window — set the expiry
            redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
        }

        if (count > limit) {
            log.debug("[RateLimit] {} blocked — count={} limit={} ip={}", tier.get().keyPrefix, count, limit, clientIp);
            res.setStatus(429);
            res.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write(
                    "{\"message\":\"Rate limit exceeded. Max " + limit +
                    " requests per minute. Try again in " + WINDOW_SECONDS + " seconds.\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private enum Tier {
        INGEST("ingest", INGEST_LIMIT),
        READ("read",   READ_LIMIT);

        final String keyPrefix;
        final int    limit;

        Tier(String keyPrefix, int limit) {
            this.keyPrefix = keyPrefix;
            this.limit     = limit;
        }
    }

    /**
     * Maps the incoming request to a rate-limit tier, or {@link Optional#empty()}
     * if the path is not subject to rate limiting.
     */
    private static Optional<Tier> resolveTier(HttpServletRequest req) {
        String method = req.getMethod();
        String path   = req.getRequestURI();

        if ("POST".equalsIgnoreCase(method) && path.startsWith("/v1/events/ingest")) {
            return Optional.of(Tier.INGEST);
        }
        if ("GET".equalsIgnoreCase(method) &&
                (path.startsWith("/v1/stats/") || path.equals("/v1/events/samples"))) {
            return Optional.of(Tier.READ);
        }
        return Optional.empty();
    }

    /**
     * Extracts the real client IP.
     * {@code X-Forwarded-For} may contain a comma-separated list of IPs
     */
    private static String resolveClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
